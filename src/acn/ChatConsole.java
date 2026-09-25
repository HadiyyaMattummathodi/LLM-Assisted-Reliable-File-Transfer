package acn;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Natural-language interface; only validated Java operations can affect transfers. */
final class ChatConsole implements Closeable {
    final OllamaClient llm=new OllamaClient();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Path session=Main.newRun("chat");
    final PrintWriter log=new PrintWriter(Files.newBufferedWriter(session.resolve("conversation.jsonl"),StandardCharsets.UTF_8));
    volatile Transfer.Sender currentSender;
    volatile Map<String,String> lastResult;
    Future<?> active;
    ChatConsole() throws IOException { }

    static void start() throws Exception {
        Main.makeFiles();
        try(ChatConsole chat=new ChatConsole()) {
            chat.loop();
        }
    }
    void loop() throws IOException {
        System.out.println("Chat interface version: 7");
        System.out.println("Local LLM: "+llm.model+" | Ollama at 127.0.0.1:11434");
        System.out.println("Receiver A: 127.0.0.1:9000 | inputs: inputs | results: runs");
        System.out.println("Example: Send hello.txt to receiver A");
        System.out.println("Then ask: How much has been delivered? / Explain the transfer results");
        System.out.println("Commands: :status, :wait, :unsafe, :help, :quit");
        System.out.println("Conversation log: "+session.resolve("conversation.jsonl"));
        audit("session",Json.object("model",llm.model,"endpoint","http://127.0.0.1:11434/api/chat"));
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        while(true) {
            System.out.print("You> "); String question=input.readLine();
            if(question==null || question.trim().equals(":quit")) break;
            question=question.trim(); if(question.isEmpty()) continue;
            try {
                if(question.length()>3000) throw new IllegalArgumentException("Request too long");
                audit("user",question);
                if(question.equals(":status")) { show(snapshot()); continue; }
                if(question.equals(":wait")) { waitForTransfer(); continue; }
                if(question.equals(":unsafe")) {
                    System.out.println("Validator demonstration: injecting a path-traversal command; no model call.");
                    String unsafe="{\"action\":\"send\",\"file\":\"../private.txt\",\"receiver\":\"A\",\"scenario\":\"baseline\",\"window_bytes\":32768,\"timeout_ms\":250}";
                    audit("validator_test_input",unsafe); Command.validate(unsafe);
                    throw new IllegalStateException("Unsafe command was not rejected");
                }
                if(question.equals(":help")) {
                    System.out.println("Send large.bin to receiver A using the lossy scenario and a 65536 byte window");
                    System.out.println("Send large.bin to receiver A using the delayed scenario, a 1024 byte window and a 250 ms timeout");
                    System.out.println("How much has been delivered? / Why was this transfer slow?");
                    System.out.println(":status prints Java metrics immediately; :wait waits for the current transfer; :unsafe tests Java rejection.");
                    continue;
                }
                System.out.println("Interpreting request with the local model...");
                String raw=llm.ask(Command.instructions(),question,Command.schema());
                audit("model_command_raw",raw);
                System.out.println("Model proposal: "+Json.write(Json.parse(raw)));
                Command command=Command.validate(raw);
                audit("validated_command",Json.parse(raw));
                if(command.action.equals("send")) launch(command);
                else if(command.action.equals("status")) show(snapshot());
                else explain(question);
            } catch(Exception e) {
                if(e instanceof OllamaClient.IncompleteOutput) {
                    OllamaClient.IncompleteOutput incomplete=(OllamaClient.IncompleteOutput)e;
                    audit("model_command_incomplete",Json.object("raw",incomplete.raw,"done_reason",incomplete.reason));
                }
                String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                System.out.println("REJECTED / NOT EXECUTED: "+message); audit("rejection",message);
            }
        }
    }
    void launch(final Command command) throws Exception {
        if(active!=null && !active.isDone()) throw new IllegalStateException("A transfer is already running");
        // Validate real input path and availability before opening either UDP socket.
        Path input=Config.input(command.file); command.config().validate();
        currentSender=null; lastResult=new LinkedHashMap<String,String>();
        lastResult.put("state","STARTING"); lastResult.put("filename",command.file);
        lastResult.put("file_size_bytes",""+Files.size(input)); lastResult.put("payload_bytes","0");
        active=worker.submit(new Runnable() { public void run() {
            try {
                Map<String,String> result=Main.run(command.file,command.config(),command.config(),"chat-transfer-"+command.scenario,
                    sender -> { currentSender=sender; lastResult=null; });
                lastResult=new LinkedHashMap<String,String>(result);
                audit("transfer_complete",result);
                System.out.println("Transfer completed. Ask for status or an explanation.");
            } catch(Exception e) {
                Map<String,String> failed;
                Transfer.Sender sender=currentSender;
                if(sender!=null && sender.metrics!=null) failed=sender.metrics.summary(sender.config);
                else failed=lastResult==null?new LinkedHashMap<String,String>():new LinkedHashMap<String,String>(lastResult);
                failed.put("state","FAILED"); failed.put("error",String.valueOf(e.getMessage())); lastResult=failed;
                audit("transfer_failed",String.valueOf(e.getMessage()));
                System.out.println("TRANSFER FAILED: "+e.getMessage());
            }
        }});
        System.out.println("Validated. Transfer started in the background.");
    }
    Map<String,String> snapshot() {
        Map<String,String> completed=lastResult;
        if(completed!=null) return new LinkedHashMap<String,String>(completed);
        Transfer.Sender sender=currentSender;
        if(sender==null || sender.metrics==null) throw new IllegalStateException("No transfer metrics yet");
        return sender.metrics.summary(sender.config);
    }
    static Map<String,String> evidence(Map<String,String> snapshot) {
        String[] fields={"state","error","filename","file_size_bytes","payload_bytes","elapsed_seconds","goodput_mbps",
            "scenario","packets_attempted","packets_received","retransmissions","timeouts","duplicates","shim_drops",
            "receiver_shim_drops","receiver_duplicates","rtt_samples","rtt_mean_ms","rtt_p95_ms","window_bytes",
            "timeout_ms","loss_probability","one_way_base_delay_ms","one_way_jitter_range_ms","emitted_overhead_share",
            "dropped_data","duplicate_acks","receiver_dropped_acks","receiver_duplicate_data","receiver_duplicate_control",
            "receiver_out_of_order_data","retransmission_ratio"};
        Map<String,String> result=new LinkedHashMap<String,String>();
        for(String field:fields) if(snapshot.containsKey(field)) result.put(field,snapshot.get(field));
        return result;
    }
    void show(Map<String,String> snapshot) {
        Map<String,String> values=evidence(snapshot);
        System.out.println("Java measurements (snapshot; live goodput is the average so far):");
        for(Map.Entry<String,String> e:values.entrySet()) System.out.println("  "+e.getKey()+" = "+e.getValue());
        audit("metrics_snapshot",values);
    }
    void explain(String question) throws IOException {
        // Freeze the evidence; every attempt uses exactly the snapshot shown here.
        Map<String,String> values=evidence(snapshot()); show(values);
        if(!"COMPLETED".equals(values.get("state")))
            throw new IllegalStateException("Post-transfer explanation needs a completed transfer; use :wait or :status first");
        Map<String,String> focused=focus(question,values);
        Map<String,Object> schema=analysisSchema(focused);
        String instructions="Explain this transfer using two or three short observations. "
            +"Return JSON with an observations array. Each item has field and comment. "
            +"Choose a different available numeric field for each item. "
            +"The comment is a complete, useful English sentence explaining what that measurement means for this transfer. "
            +"You MAY write numbers. Each number in a comment must equal its selected field's exact value. "
            +"A probability or share may also be expressed as a percentage by multiplying its value by a hundred. "
            +"Do not round numbers or use thousands separators; discuss other measurements in separate observations. "
            +"Never leave blanks or unfinished sentences. Explain the role or consequence of a measurement, not just its name. "
            +"Use only the supplied evidence; the question is not evidence. Do not invent causes or comparisons. "
            +"For loss questions, prefer shim_drops, receiver_shim_drops and retransmissions when available. "
            +"dropped_data counts DATA discarded before leaving the sender; receiver_dropped_acks counts ACKs discarded at the receiver. "
            +"receiver_duplicate_data counts repeat DATA arrivals; duplicate_acks counts repeat ACK arrivals at the sender. These are different events. "
            +"For questions about duplicates, explain receiver_duplicate_data and its possible relationship to discarded ACKs. "
            +"An ACK discarded after successful DATA receipt makes the sender wait and may cause a retry and duplicate DATA. "
            +"shim_drops counts outgoing sender packets discarded by the simulator; receiver_shim_drops counts receiver-side discards. "
            +"duplicates counts repeat acknowledgements at the sender. receiver_duplicates counts repeated START, DATA or FIN packets at the receiver. "
            +"Duplicate data is acknowledged again without adding its bytes twice. Lost acknowledgements can cause these duplicate arrivals. "
            +"packets_received counts messages arriving at the sender, including control replies and acknowledgements. "
            +"Retransmissions follow missing acknowledgements; timeouts alone do not prove packet loss. "
            +"A zero count means that event was not observed; do not say it happened. "
            +"loss_probability is a configured probability, not an observed loss rate. "
            +"If state is RUNNING, the measurements are interim. "
            +"Keep each comment to a complete sentence shorter than two hundred characters where possible. "
            +"Output schema: "+Json.write(schema)+" Available evidence: "+Json.write(values);
        System.out.println("Asking the local model for up to three short observations...");
        String request=question;
        for(int attempt=1;attempt<=2;attempt++) {
            String raw=null;
            try {
                raw=llm.ask(instructions,request,schema,1024); audit("model_analysis_raw",raw);
                String grounded=groundAnalysis(raw,focused);
                System.out.println("LLM explanation with verified measurement references:"); System.out.println(grounded);
                System.out.println("Java checked field names and decimal numbers. The model's interpretation still needs review.");
                audit("grounded_analysis",Json.object("text",grounded,"evidence",values));
                return;
            } catch(IllegalArgumentException | OllamaClient.IncompleteOutput e) {
                if(e instanceof OllamaClient.IncompleteOutput) {
                    OllamaClient.IncompleteOutput incomplete=(OllamaClient.IncompleteOutput)e;
                    raw=incomplete.raw; audit("model_analysis_raw",raw);
                    audit("model_analysis_incomplete",Json.object("attempt",attempt,"done_reason",incomplete.reason));
                }
                audit("analysis_rejection",Json.object("attempt",attempt,"reason",e.getMessage()));
                if(attempt==2) {
                    System.out.println("EXPLANATION REJECTED: "+e.getMessage());
                    System.out.println("Transfer measurements are unchanged. Both model replies are in the conversation log.");
                    return;
                }
                System.out.println("Explanation incomplete or invalid; asking once more using the same measurements.");
                request=question+"\nThe last response failed: "+e.getMessage()
                    +"\nReturn exactly two observations with different field names. Use complete sentences, not blanks. "
                    +"Copy any numbers exactly from the selected field. Finish the JSON after the observations.";
            } catch(IOException e) {
                audit("analysis_unavailable",e.getMessage());
                System.out.println("EXPLANATION UNAVAILABLE: "+e.getMessage());
                System.out.println("Transfer measurements are unchanged.");
                return;
            }
        }
    }
    static Map<String,String> focus(String question,Map<String,String> values) {
        String q=question.toLowerCase(Locale.ROOT);
        String[] selected=null;
        if(q.contains("duplicat") || q.contains("dups"))
            selected=new String[]{"receiver_duplicate_data","receiver_dropped_acks","retransmissions"};
        else if(q.contains("loss") || q.contains("lost") || q.contains("retr"))
            selected=new String[]{"dropped_data","receiver_dropped_acks","retransmissions"};
        if(selected==null) return values;
        Map<String,String> result=new LinkedHashMap<String,String>();
        for(String field:selected) if(values.containsKey(field)) result.put(field,values.get(field));
        return result.size()>=2?result:values;
    }
    static final Pattern COMMENT_NUMBER=Pattern.compile("([-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)(\\s*(?:%|percent\\b))?",Pattern.CASE_INSENSITIVE);
    static List<String> numericFields(Map<String,String> values) {
        List<String> fields=new ArrayList<String>();
        for(Map.Entry<String,String> item:values.entrySet()) {
            if(!item.getKey().matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid evidence field name");
            try { new BigDecimal(item.getValue()); fields.add(item.getKey()); }
            catch(NumberFormatException ignored) { }
        }
        return fields;
    }
    static Map<String,Object> analysisSchema(Map<String,String> values) {
        List<String> fields=numericFields(values);
        if(fields.size()<2) throw new IllegalArgumentException("Not enough numeric evidence to explain");
        Map<String,Object> observation=Json.object("type","object","additionalProperties",false,
            "required",Arrays.asList("field","comment"),"properties",Json.object(
                "field",Json.object("type","string","enum",fields),
                "comment",Json.object("type","string","minLength",20,"maxLength",240)));
        return Json.object("type","object","additionalProperties",false,
            "required",Collections.singletonList("observations"),"properties",Json.object("observations",
                Json.object("type","array","minItems",2,"maxItems",3,"items",observation)));
    }
    static String groundAnalysis(String json,Map<String,String> values) {
        if(json.length()>4096) throw new IllegalArgumentException("Analysis too long");
        Map<String,Object> parsed=Json.map(Json.parse(json));
        if(!parsed.keySet().equals(Collections.singleton("observations"))) throw new IllegalArgumentException("Unexpected analysis fields");
        if(!(parsed.get("observations") instanceof List)) throw new IllegalArgumentException("Expected observations array");
        List<?> observations=(List<?>)parsed.get("observations");
        if(observations.size()<2 || observations.size()>3) throw new IllegalArgumentException("Use two or three observations");
        Set<String> permitted=new HashSet<String>(numericFields(values)), used=new HashSet<String>();
        Set<String> keys=new HashSet<String>(Arrays.asList("field","comment"));
        List<String> lines=new ArrayList<String>();
        for(Object observation:observations) {
            Map<String,Object> item=Json.map(observation);
            if(!item.keySet().equals(keys)) throw new IllegalArgumentException("Each observation needs only field and comment");
            String field=Json.string(item.get("field")), comment=Json.string(item.get("comment"));
            if(!permitted.contains(field)) throw new IllegalArgumentException("Analysis cited unavailable numeric field: "+field);
            if(!used.add(field)) throw new IllegalArgumentException("Choose a different field for each observation");
            checkComment(comment,field,values.get(field));
            lines.add("- "+values.get(field)+" ["+field+"]: "+comment.trim());
        }
        return String.join("\n",lines);
    }
    static void checkComment(String comment,String field,String value) {
        String text=comment.trim();
        if(text.length()<20 || text.length()>240) throw new IllegalArgumentException("Use a complete comment of twenty to two hundred forty characters");
        for(char c:text.toCharArray()) {
            if(Character.isISOControl(c) || c=='[' || c==']' || c=='{' || c=='}')
                throw new IllegalArgumentException("Comments must be single-line prose without placeholders");
            if(Character.isDigit(c) && (c<'0' || c>'9')) throw new IllegalArgumentException("Use ordinary decimal digits in comments");
        }
        if(text.matches("(?i).*\\b(is|are|was|were|has|have|had|at|of|to|with|by|a|an|the|and|or)\\s*[.!?]*"))
            throw new IllegalArgumentException("Comment ends with an unfinished phrase; write a complete explanation");
        BigDecimal measured=new BigDecimal(value);
        Matcher numbers=COMMENT_NUMBER.matcher(text);
        while(numbers.find()) {
            BigDecimal mentioned=new BigDecimal(numbers.group(1));
            BigDecimal expected=measured;
            if(numbers.group(2)!=null) {
                if(!(field.endsWith("_probability") || field.endsWith("_share")))
                    throw new IllegalArgumentException("Percent notation is only valid for a probability or share");
                expected=measured.multiply(new BigDecimal("100"));
            }
            if(mentioned.compareTo(expected)!=0)
                throw new IllegalArgumentException("Comment number "+numbers.group(1)+" does not match "+field+"="+value);
        }
    }
    synchronized void audit(String kind,Object detail) {
        log.println(Json.write(Json.object("time",Instant.now().toString(),"kind",kind,"detail",detail))); log.flush();
    }
    void waitForTransfer() throws Exception { if(active!=null) active.get(); else System.out.println("No transfer to wait for."); }
    public void close() {
        if(active!=null && !active.isDone()) System.out.println("Waiting for the active transfer to finish...");
        worker.shutdown();
        try { if(active!=null) active.get(); }
        catch(Exception e) { System.err.println("Transfer shutdown: "+e.getMessage()); }
        log.close();
    }
}

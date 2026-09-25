package acn;

import java.util.*;

/** Tests the trust boundary without needing a model or trusting its behaviour. */
final class InterfaceTests {
    static int count;
    static final String GOOD="{\"action\":\"send\",\"file\":\"hello.txt\",\"receiver\":\"A\",\"scenario\":\"baseline\",\"window_bytes\":32768,\"timeout_ms\":250}";
    static void reject(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) {count++;return;}
        throw new AssertionError("Invalid input accepted");
    }
    static void check(boolean condition) { if(!condition) throw new AssertionError("Interface check failed"); count++; }
    static Map<String,Object> observation(String field,String comment) { return Json.object("field",field,"comment",comment); }
    static String analysis(Object... observations) { return Json.write(Json.object("observations",Arrays.asList(observations))); }
    static void run() {
        count=0;
        Command send=Command.validate(GOOD); check(send.file.equals("hello.txt") && send.config().windowBytes==32768);
        String status="{\"action\":\"status\",\"file\":\"\",\"receiver\":\"\",\"scenario\":\"\",\"window_bytes\":0,\"timeout_ms\":0}";
        check(Command.validate(status).action.equals("status"));
        check(Command.validate(status.replace("status","explain")).action.equals("explain"));
        reject(() -> Command.validate(GOOD.replace("hello.txt","../private.txt")));
        reject(() -> Command.validate(GOOD.replace("hello.txt","C:/private.txt")));
        reject(() -> Command.validate(GOOD.replace("\"A\"","\"B\"")));
        reject(() -> Command.validate(GOOD.replace("baseline","unknown")));
        reject(() -> Command.validate(GOOD.replace("baseline","")));
        reject(() -> Command.validate(GOOD.replace("32768","1000000")));
        reject(() -> Command.validate(GOOD.replace("250","0")));
        reject(() -> Command.validate(GOOD.replace("32768","\"32768\"")));
        reject(() -> Command.validate(GOOD.replace("32768","32768.5")));
        reject(() -> Command.validate(GOOD.replace("32768","9999999999999999999")));
        reject(() -> Command.validate(GOOD.replace("send","shell")));
        reject(() -> Command.validate(GOOD.substring(0,GOOD.length()-1)+",\"shell\":\"dir\"}"));
        reject(() -> Command.validate(GOOD.replace("\"receiver\":\"A\",","")));
        reject(() -> Command.validate(GOOD.substring(0,GOOD.length()-1)+",\"action\":\"send\"}"));
        reject(() -> Command.validate(GOOD+" trailing"));
        reject(() -> Command.validate("```json\n"+GOOD+"\n```"));
        reject(() -> Command.validate(status.replace("\"file\":\"\"","\"file\":\"hello.txt\"")));
        reject(() -> Command.validate(GOOD.replace("\"hello.txt\"","null")));
        Map<String,String> values=new LinkedHashMap<String,String>();
        values.put("payload_bytes","46"); values.put("retransmissions","0"); values.put("rtt_mean_ms","NA");
        String goodAnalysis=analysis(observation("payload_bytes","These bytes were acknowledged."),
            observation("retransmissions","These are repeat send attempts."));
        String grounded=ChatConsole.groundAnalysis(goodAnalysis,values);
        check(grounded.contains("46 [payload_bytes]") && grounded.contains("0 [retransmissions]"));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.","Delivered 999 bytes."),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("retransmissions","invented_metric"),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("retransmissions","rtt_mean_ms"),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("retransmissions","payload_bytes"),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.","[payload_bytes]"),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.",""),values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.","   "),values));
        String longComment=String.join("",Collections.nCopies(241,"x"));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.",longComment),values));
        reject(() -> ChatConsole.groundAnalysis(analysis(observation("payload_bytes","Bytes acknowledged.")),values));
        reject(() -> ChatConsole.groundAnalysis(analysis(),values));
        reject(() -> ChatConsole.groundAnalysis(analysis(observation("payload_bytes","Bytes."),observation("retransmissions","Retries."),
            observation("payload_bytes","Bytes."),observation("retransmissions","Retries.")),values));
        reject(() -> ChatConsole.groundAnalysis("{\"observations\":{}}",values));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis.substring(0,goodAnalysis.length()-1)+",\"action\":\"send\"}",values));
        reject(() -> ChatConsole.groundAnalysis(analysis(Json.object("field","payload_bytes","comment","Bytes.","value",999),
            observation("retransmissions","Retries.")),values));
        values.put("shim_drops","31");
        String three=analysis(observation("payload_bytes","The receiver acknowledged 46 bytes."),observation("retransmissions","There were 0 repeat send attempts."),
            observation("shim_drops","The sender simulator discarded 31 packets."));
        check(ChatConsole.groundAnalysis(three,values).contains("31 [shim_drops]"));
        check(!ChatConsole.numericFields(values).contains("rtt_mean_ms"));
        check(ChatConsole.groundAnalysis(goodAnalysis.replace("These bytes were acknowledged.","The receiver acknowledged 46 bytes."),values).contains("acknowledged 46 bytes"));
        reject(() -> ChatConsole.checkComment("The retransmissions are      .","retransmissions","67"));
        reject(() -> ChatConsole.checkComment("The loss probability is        .","loss_probability","0.03"));
        reject(() -> ChatConsole.checkComment("The packets received are     .","packets_received","1026"));
        ChatConsole.checkComment("The configured loss probability is 3% per outgoing packet.","loss_probability","0.03"); count++;
        ChatConsole.checkComment("The configured loss probability is 3 percent per outgoing packet.","loss_probability","0.03"); count++;
        reject(() -> ChatConsole.checkComment("The configured loss probability is 0.03% per packet.","loss_probability","0.03"));
        reject(() -> ChatConsole.checkComment("There were 67% retransmissions during the transfer.","retransmissions","67"));
        reject(() -> ChatConsole.checkComment("There were 31 retransmissions during the transfer.","retransmissions","67"));
        reject(() -> ChatConsole.checkComment("The sender made 67 retries and received 36 duplicates.","retransmissions","67"));
        ChatConsole.checkComment("The sender made 67 retries while waiting for acknowledgements.","retransmissions","67"); count++;
        ChatConsole.checkComment("The sender retried because acknowledgements did not arrive in time.","retransmissions","67"); count++;
        ChatConsole.checkComment("The measured average useful transfer rate was 2.007219 Mbps.","goodput_mbps","2.007219"); count++;
        reject(() -> ChatConsole.checkComment("The measured average useful transfer rate was 2.01 Mbps.","goodput_mbps","2.007219"));
        check(Json.string(Json.parse(Json.write("quotes\" slash\\ newline\n"))).equals("quotes\" slash\\ newline\n"));
        reject(() -> Json.parse("{\"x\":01}")); reject(() -> Json.parse("{\"x\":1,}"));
        reject(() -> Json.parse("[1,]")); reject(() -> Json.parse("{\"x\":NaN}"));
        values.put("dropped_data","31"); values.put("receiver_dropped_acks","36"); values.put("receiver_duplicate_data","36");
        Map<String,String> duplicates=ChatConsole.focus("Why are there duplicates?",values);
        check(duplicates.containsKey("receiver_duplicate_data") && !duplicates.containsKey("payload_bytes"));
        Map<String,String> loss=ChatConsole.focus("Summarize packet loss and retries",values);
        check(loss.keySet().equals(new LinkedHashSet<String>(Arrays.asList("dropped_data","receiver_dropped_acks","retransmissions"))));
        reject(() -> ChatConsole.groundAnalysis(goodAnalysis,loss));
        System.out.println("PASS: "+count+" strict JSON, command validation and measurement-reference checks.");
    }
}

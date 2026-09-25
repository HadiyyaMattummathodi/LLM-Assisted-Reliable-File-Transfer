package acn;

import java.util.*;

/** Deterministic boundary: model text cannot invoke arbitrary code or endpoints. */
final class Command {
    final String action,file,receiver,scenario;
    final int windowBytes,timeoutMs;
    Command(String action,String file,String receiver,String scenario,int window,int timeout) {
        this.action=action; this.file=file; this.receiver=receiver; this.scenario=scenario;
        this.windowBytes=window; this.timeoutMs=timeout;
    }
    static Command validate(String json) {
        if(json.length()>4096) throw new IllegalArgumentException("Command too long");
        Map<String,Object> m=Json.map(Json.parse(json));
        Set<String> keys=new HashSet<String>(Arrays.asList("action","file","receiver","scenario","window_bytes","timeout_ms"));
        if(!m.keySet().equals(keys)) throw new IllegalArgumentException("Command has missing or unknown fields");
        Command c=new Command(Json.string(m.get("action")),Json.string(m.get("file")),Json.string(m.get("receiver")),
            Json.string(m.get("scenario")),Json.integer(m.get("window_bytes")),Json.integer(m.get("timeout_ms")));
        if(c.action.equals("send")) {
            Config.validateName(c.file);
            if(!c.receiver.equals("A")) throw new IllegalArgumentException("Only receiver A is permitted");
            if(!Arrays.asList("baseline","lossy","delayed","corrupt").contains(c.scenario))
                throw new IllegalArgumentException("Scenario is not allowlisted");
            c.config().validate();
        } else if(c.action.equals("status") || c.action.equals("explain")) {
            if(!c.file.isEmpty() || !c.receiver.isEmpty() || !c.scenario.isEmpty() || c.windowBytes!=0 || c.timeoutMs!=0)
                throw new IllegalArgumentException("Read-only command must not contain transfer parameters");
        } else throw new IllegalArgumentException("Unsupported or ambiguous request");
        return c;
    }
    Config config() {
        Config c=Config.scenario(scenario); c.windowBytes=windowBytes; c.timeoutMs=timeoutMs; c.validate(); return c;
    }
    static Map<String,Object> schema() {
        // Separate generation shapes prevent query-only blanks leaking into a send.
        // Explicit invalid nonempty values remain possible so Java can reject them.
        Map<String,Object> send=shape(Json.object(
            "action",Json.object("type","string","enum",Collections.singletonList("send")),
            "file",Json.object("type","string","minLength",1),
            "receiver",Json.object("type","string","minLength",1),
            "scenario",Json.object("type","string","minLength",1,"description","Use baseline when the user omits the scenario"),
            "window_bytes",Json.object("type","integer"),"timeout_ms",Json.object("type","integer")));
        Map<String,Object> readOnly=shape(Json.object(
            "action",Json.object("type","string","enum",Arrays.asList("status","explain","reject")),
            "file",Json.object("type","string","enum",Collections.singletonList("")),
            "receiver",Json.object("type","string","enum",Collections.singletonList("")),
            "scenario",Json.object("type","string","enum",Collections.singletonList("")),
            "window_bytes",Json.object("type","integer","enum",Collections.singletonList(0)),
            "timeout_ms",Json.object("type","integer","enum",Collections.singletonList(0))));
        // Do not mix top-level properties with anyOf: grammar converters may ignore that combination.
        return Json.object("anyOf",Arrays.asList(send,readOnly));
    }
    static Map<String,Object> shape(Map<String,Object> properties) {
        return Json.object("type","object","additionalProperties",false,
            "required",Arrays.asList("action","file","receiver","scenario","window_bytes","timeout_ms"),
            "properties",properties);
    }
    static String instructions() {
        return "Translate the user's request into one JSON command with exactly six fields. Do not execute anything. "
          +"Actions: send, status, explain, reject. For send: preserve the exact filename and receiver requested; "
          +"defaults are receiver A, scenario baseline, window_bytes 32768, timeout_ms 250. "
          +"For a send request, if the user does not mention a scenario, you MUST write scenario=baseline. "
          +"Never output an empty file, receiver or scenario for send. "
          +"Convert KB/KiB to bytes using 1024. Preserve unsafe paths, unknown receivers and out-of-range numbers exactly: "
          +"Java must reject them. Never silently replace an explicit invalid value with an allowed one. "
          +"Scenarios: baseline, lossy, delayed, corrupt. For status/explain/reject use empty strings for file, receiver, scenario "
          +"and zero for window_bytes and timeout_ms. Questions about progress, bytes delivered or current throughput mean status. "
          +"Questions asking why, reasons, analysis or summarise retries mean explain. "
          +"Unsupported requests, shell commands, requested code execution, missing filenames for send, or ambiguous requests mean reject. "
          +"Example: 'Send hello.txt to receiver A' -> "
          +"{\"action\":\"send\",\"file\":\"hello.txt\",\"receiver\":\"A\",\"scenario\":\"baseline\",\"window_bytes\":32768,\"timeout_ms\":250}. "
          +"Example: 'How much has arrived?' -> {\"action\":\"status\",\"file\":\"\",\"receiver\":\"\",\"scenario\":\"\",\"window_bytes\":0,\"timeout_ms\":0}. "
           +"The output must match this schema: "+Json.write(schema())
          +" Final rule: a SEND uses scenario baseline unless the user explicitly requests a different scenario. "
          +"Empty strings and zero query parameters belong only to status/explain/reject, never to send.";
    }
}

package acn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed localhost HTTP endpoint. No API key, tools, shell or transferred file content. */
final class OllamaClient {
    static final class IncompleteOutput extends IOException {
        final String raw,reason;
        IncompleteOutput(String raw,String reason) {
            super("Model reply was cut off or incomplete ("+reason+")"); this.raw=raw; this.reason=reason;
        }
    }
    final String model;
    OllamaClient() {
        String configured=System.getenv("RFT_MODEL");
        model=configured==null || configured.trim().isEmpty()?"qwen2.5:1.5b":configured.trim();
        if(!Arrays.asList("qwen2.5:1.5b","qwen2.5:3b","qwen2.5:7b").contains(model))
            throw new IllegalArgumentException("RFT_MODEL must be qwen2.5:1.5b, qwen2.5:3b or qwen2.5:7b");
    }
    String ask(String system,String user,Map<String,Object> schema) throws IOException {
        return ask(system,user,schema,512);
    }
    String ask(String system,String user,Map<String,Object> schema,int tokenLimit) throws IOException {
        Map<String,Object> request=Json.object("model",model,"stream",false,"format",schema,"keep_alive","10m",
            "options",Json.object("temperature",0,"num_ctx",4096,"num_predict",tokenLimit),
            "messages",Arrays.asList(Json.object("role","system","content",system),Json.object("role","user","content",user)));
        byte[] bytes=Json.write(request).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:11434/api/chat").openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(180000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type","application/json"); connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try(OutputStream out=connection.getOutputStream()) { out.write(bytes); }
            int status=connection.getResponseCode();
            String body=read(status==200?connection.getInputStream():connection.getErrorStream());
            if(status!=200) throw new IOException("Ollama HTTP "+status+": "+body.substring(0,Math.min(500,body.length())));
            Map<String,Object> response=Json.map(Json.parse(body));
            Map<String,Object> message=Json.map(response.get("message"));
            if(message.containsKey("tool_calls") && message.get("tool_calls")!=null
                && (!(message.get("tool_calls") instanceof List) || !((List<?>)message.get("tool_calls")).isEmpty()))
                throw new IOException("Model tool calls are not allowed");
            String raw=Json.string(message.get("content"));
            if(!Boolean.TRUE.equals(response.get("done")) || "length".equals(response.get("done_reason")))
                throw new IncompleteOutput(raw,String.valueOf(response.get("done_reason")));
            return raw;
        } catch(ConnectException e) {
            throw new IOException("Cannot reach Ollama. Open Ollama, then run: ollama pull "+model);
        } finally { connection.disconnect(); }
    }
    static String read(InputStream input) throws IOException {
        if(input==null) return "";
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[4096]; int count;
            while((count=in.read(chunk))!=-1) {
                if(out.size()+count>262144) throw new IOException("Model response too large"); out.write(chunk,0,count);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}

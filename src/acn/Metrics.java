package acn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** All numbers originate in engine events, never in an LLM response. */
final class Metrics implements Closeable {
    final String role;
    final long origin=System.nanoTime();
    final PrintWriter log;
    long attempts, emitted, received, acknowledged, timeouts, duplicates, retransmissions;
    long drops, corruptions, invalid, attemptedBytes, emittedBytes, receivedBytes, payloadBytes;
    long dataAttempts, dataRetransmissions;
    long dataDrops, ackDrops, controlDrops, dataDuplicates, ackDuplicates, controlDuplicates;
    long outOfOrder, cancelled, cancelledBytes;
    long end;
    String state="RUNNING", sha256="", filename="", transferId="";
    long fileSize;
    final List<Double> rtts=new ArrayList<Double>();

    Metrics(Path folder,String role) throws IOException {
        Files.createDirectories(folder); this.role=role;
        log=new PrintWriter(Files.newBufferedWriter(folder.resolve(role+"-events.csv"),StandardCharsets.UTF_8));
        log.println("elapsed_ms,role,transfer_id,event,type,sequence,bytes,detail");
    }
    synchronized void event(String event,Packet p,int bytes,String detail) {
        if(p!=null && transferId.isEmpty()) transferId=p.id.toString();
        if(event.equals("SEND")) { attempts++; attemptedBytes+=bytes; if(p.type==Packet.DATA) dataAttempts++; }
        if(event.equals("EMIT")) { emitted++; emittedBytes+=bytes; }
        if(event.equals("RECEIVE")) { received++; receivedBytes+=bytes; }
        if(event.equals("RECEIVE_INVALID")) { received++; receivedBytes+=bytes; invalid++; }
        if(event.equals("ACKNOWLEDGED")) acknowledged++;
        if(event.equals("TIMEOUT")) timeouts++;
        if(event.equals("DUPLICATE")) {
            duplicates++;
            if(p.type==Packet.DATA) dataDuplicates++;
            else if(p.type==Packet.ACK) ackDuplicates++;
            else controlDuplicates++;
        }
        if(event.equals("RETRANSMIT")) { retransmissions++; if(p.type==Packet.DATA) dataRetransmissions++; }
        if(event.equals("DROP")) {
            drops++;
            if(p.type==Packet.DATA) dataDrops++;
            else if(p.type==Packet.ACK) ackDrops++;
            else controlDrops++;
        }
        if(event.equals("OUT_OF_ORDER")) outOfOrder++;
        if(event.equals("CANCEL")) { cancelled++; cancelledBytes+=bytes; }
        if(event.equals("CORRUPT")) corruptions++;
        if(event.equals("ACCEPT_DATA") || event.equals("CONFIRM_DATA")) payloadBytes+=bytes;
        log.printf(Locale.US,"%.3f,%s,%s,%s,%s,%d,%d,%s%n",(System.nanoTime()-origin)/1e6,
            role,p==null?"":p.id,event,p==null?"UNKNOWN":Packet.name(p.type),p==null?-1:p.sequence,
            bytes,quote(detail));
        // Buffered writes avoid a disk flush for each packet; close() flushes the log.
    }
    synchronized void rtt(Packet ack,double value) {
        rtts.add(value);
        event("RTT_SAMPLE",ack,0,number(value)); // Exact to a nanosecond, expressed in milliseconds.
    }
    synchronized void finish(String result) {
        if(end==0) end=System.nanoTime();
        state=result;
        event("STATE",null,0,result);
    }
    synchronized Map<String,String> summary(Config c) {
        Map<String,String> m=new LinkedHashMap<String,String>();
        double seconds=((end==0?System.nanoTime():end)-origin)/1e9;
        m.put("role",role); m.put("state",state); m.put("transfer_id",transferId);
        m.put("filename",filename); m.put("file_size_bytes",""+fileSize); m.put("payload_bytes",""+payloadBytes);
        m.put("elapsed_seconds",number(seconds)); m.put("goodput_mbps",number(payloadBytes*8.0/seconds/1e6));
        m.put("emitted_udp_mbps",number(emittedBytes*8.0/seconds/1e6));
        m.put("packets_attempted",""+attempts); m.put("packets_emitted",""+emitted);
        m.put("packets_received",""+received); m.put("packets_acknowledged",""+acknowledged);
        m.put("timeouts",""+timeouts); m.put("duplicates",""+duplicates); m.put("retransmissions",""+retransmissions);
        m.put("duplicate_data",""+dataDuplicates); m.put("duplicate_acks",""+ackDuplicates);
        m.put("duplicate_control",""+controlDuplicates); m.put("out_of_order_data",""+outOfOrder);
        m.put("data_attempts",""+dataAttempts); m.put("data_retransmissions",""+dataRetransmissions);
        m.put("retransmission_ratio",number(attempts==0?0:retransmissions/(double)attempts));
        m.put("shim_drops",""+drops); m.put("shim_corruptions",""+corruptions); m.put("invalid_packets",""+invalid);
        m.put("dropped_data",""+dataDrops); m.put("dropped_acks",""+ackDrops); m.put("dropped_control",""+controlDrops);
        m.put("cancelled_packets",""+cancelled); m.put("cancelled_udp_bytes",""+cancelledBytes);
        m.put("attempted_udp_bytes",""+attemptedBytes); m.put("emitted_udp_bytes",""+emittedBytes);
        m.put("received_udp_bytes",""+receivedBytes); m.put("rtt_samples",""+rtts.size());
        List<Double> sorted=new ArrayList<Double>(rtts); Collections.sort(sorted);
        double sum=0,variation=0; for(int i=0;i<rtts.size();i++) {
            sum+=rtts.get(i); if(i>0) variation+=Math.abs(rtts.get(i)-rtts.get(i-1));
        }
        m.put("rtt_mean_ms",rtts.isEmpty()?"NA":number(sum/rtts.size()));
        m.put("rtt_p95_ms",sorted.isEmpty()?"NA":number(sorted.get((int)Math.ceil(0.95*sorted.size())-1)));
        m.put("rtt_variation_ms",rtts.size()<2?"NA":number(variation/(rtts.size()-1)));
        m.put("sha256",sha256); m.put("scenario",c.scenario); m.put("seed",""+c.seed);
        m.put("chunk_bytes",""+c.chunk); m.put("window_bytes",""+c.windowBytes);
        m.put("timeout_ms",""+c.timeoutMs); m.put("max_retries",""+c.maxRetries);
        m.put("loss_probability",""+c.loss); m.put("corruption_probability",""+c.corruption);
        m.put("one_way_base_delay_ms",""+c.delayMs); m.put("one_way_jitter_range_ms",""+c.jitterMs);
        m.put("java_version",System.getProperty("java.version")); m.put("os",System.getProperty("os.name"));
        return m;
    }
    static String number(double x) { return String.format(Locale.US,"%.6f",x); }
    static String quote(String s) { return "\""+s.replace("\"","\"\"")+"\""; }
    static void writeCsv(Path file,List<Map<String,String>> rows) throws IOException {
        if(rows.isEmpty()) return;
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(temporary,StandardCharsets.UTF_8))) {
            Set<String> allKeys=new LinkedHashSet<String>();
            for(Map<String,String> row:rows) allKeys.addAll(row.keySet());
            List<String> keys=new ArrayList<String>(allKeys); out.println(String.join(",",keys));
            for(Map<String,String> row:rows) {
                List<String> values=new ArrayList<String>(); for(String k:keys) values.add(quote(row.containsKey(k)?row.get(k):""));
                out.println(String.join(",",values));
            }
            if(out.checkError()) throw new IOException("Could not write measurements: "+file);
        }
        try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
    }
    public synchronized void close() {
        boolean failed=log.checkError(); log.close();
        if(failed || log.checkError()) throw new UncheckedIOException(new IOException("Could not save "+role+" event log"));
    }
}

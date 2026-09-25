package acn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Recomputes metrics from raw events, rather than trusting summary counters. */
final class EvidenceAudit {
    static void run(Path root) throws Exception {
        List<Path> summaries=new ArrayList<Path>();
        try(Stream<Path> paths=Files.walk(root)) {
            paths.filter(p -> p.getFileName().toString().equals("summary.csv")).sorted().forEach(summaries::add);
        }
        if(summaries.isEmpty()) throw new IOException("No completed transfer summaries under "+root);
        int byteChecks=0;
        for(Path summary:summaries) {
            Path folder=summary.getParent();
            Map<String,String> combined=one(summary);
            Map<String,String> sender=endpoint(folder,"sender"),receiver=endpoint(folder,"receiver");
            require("COMPLETED".equals(sender.get("state")),"Sender not completed: "+folder);
            require("VERIFIED".equals(receiver.get("state")),"Receiver not verified: "+folder);
            require(sender.get("transfer_id").equals(receiver.get("transfer_id")),"Transfer IDs differ");
            require(sender.get("sha256").equals(receiver.get("sha256")),"Endpoint hashes differ");
            for(String key:sender.keySet()) require(sender.get(key).equals(combined.get(key)),"Combined sender field differs: "+key);
            equal(combined,"receiver_payload_bytes",integer(receiver,"payload_bytes"));
            equal(combined,"receiver_duplicates",integer(receiver,"duplicates"));
            equal(combined,"receiver_duplicate_data",integer(receiver,"duplicate_data"));
            equal(combined,"receiver_shim_drops",integer(receiver,"shim_drops"));
            equal(combined,"receiver_dropped_acks",integer(receiver,"dropped_acks"));
            equal(combined,"receiver_out_of_order_data",integer(receiver,"out_of_order_data"));
            long useful=integer(sender,"file_size_bytes");
            equal(sender,"payload_bytes",useful); equal(receiver,"payload_bytes",useful);
            long emitted=integer(sender,"emitted_udp_bytes")+integer(receiver,"emitted_udp_bytes");
            long attempted=integer(sender,"attempted_udp_bytes")+integer(receiver,"attempted_udp_bytes");
            equal(combined,"both_directions_emitted_udp_bytes",emitted);
            equal(combined,"both_directions_attempted_udp_bytes",attempted);
            near(combined,"emitted_overhead_share",emitted==0?0:(emitted-useful)/(double)emitted,0.000001);
            near(combined,"attempted_overhead_share",attempted==0?0:(attempted-useful)/(double)attempted,0.000001);
            // Saved output files are optional for an archived logs-only audit.
            Path actual=Paths.get(combined.get("received_file"));
            if(Files.isRegularFile(actual) && Files.isDirectory(Paths.get("inputs"))) {
                require(actual.toRealPath().startsWith(Paths.get("received").toRealPath()),"Received path outside received folder");
                byte[] source=Files.readAllBytes(Config.input(sender.get("filename")));
                byte[] output=Files.readAllBytes(actual);
                require(Arrays.equals(source,output),"Byte comparison failed: "+folder);
                require(Transfer.hex(Transfer.digest(output)).equals(sender.get("sha256")),"Saved file hash differs");
                byteChecks++;
            }
        }
        Path plan=root.resolve("plan.csv"),results=root.resolve("all-results.csv");
        int planned=0;
        if(Files.exists(plan)) {
            List<Map<String,String>> plans=readCsv(plan),rows=readCsv(results);
            planned=plans.size(); require(rows.size()==planned,"Batch is incomplete");
            Set<String> folders=new HashSet<String>();
            for(int i=0;i<planned;i++) {
                Map<String,String> p=plans.get(i),r=rows.get(i);
                for(String key:p.keySet()) require(p.get(key).equals(r.get(key)),"Trial differs from plan: "+key);
                require("COMPLETED".equals(r.get("trial_status")),"Batch contains a failed trial");
                require(folders.add(r.get("trial_folder")),"Duplicate trial folder");
                Map<String,String> saved=one(root.resolve(r.get("trial_folder")).resolve("summary.csv"));
                for(String key:saved.keySet()) require(saved.get(key).equals(r.get(key)),"Trial differs from its summary: "+key);
            }
            require(summaries.size()==planned+1,"Expected planned trials plus one warm-up");
        }
        String report="PASS: "+summaries.size()+" completed transfers; "+(summaries.size()*2)+" endpoint event logs reconciled.\n"
            +"Checks: counters, byte totals, drop/duplicate types, sequence/window limits, unique delivery, every RTT sample, mean/p95/variation, goodput and overhead.\n"
            +"Planned measured trials reconciled: "+planned+". Independent saved-file byte/hash checks: "+byteChecks+".\n"
            +"A logs-only archive cannot independently prove the bytes of missing source/output files.\n";
        Files.write(root.resolve("audit-report.txt"),report.getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }

    static Map<String,String> endpoint(Path folder,String role) throws Exception {
        Map<String,String> summary=one(folder.resolve(role+"-summary.csv"));
        List<Map<String,String>> events=readCsv(folder.resolve(role+"-events.csv"));
        Map<String,Long> counts=new HashMap<String,Long>(),bytes=new HashMap<String,Long>();
        Set<Integer> unique=new HashSet<Integer>();
        Map<Integer,Integer> sends=new HashMap<Integer,Integer>();
        BitSet confirmed=new BitSet(); int base=0;
        List<Double> rtts=new ArrayList<Double>();
        Set<Integer> rttSequences=new HashSet<Integer>();
        long payload=0; double previous=-1;
        int chunk=(int)integer(summary,"chunk_bytes"),window=(int)integer(summary,"window_bytes")/chunk;
        int chunks=(int)((integer(summary,"file_size_bytes")+chunk-1)/chunk);
        for(Map<String,String> e:events) {
            String kind=e.get("event"),type=e.get("type");
            int seq=Integer.parseInt(e.get("sequence")); long size=integer(e,"bytes");
            double time=Double.parseDouble(e.get("elapsed_ms"));
            require(time>=previous,"Event clock went backwards"); previous=time;
            add(counts,kind,1); add(bytes,kind,size); add(counts,kind+"_"+type,1);
            if(kind.equals("SEND") && type.equals("DATA")) {
                int count=sends.containsKey(seq)?sends.get(seq):0;
                if(count==0) require(seq>=base && seq<base+window,"Sender exceeded the sequence window");
                sends.put(seq,count+1);
            }
            if(kind.equals("ACKNOWLEDGED") && type.equals("ACK")) {
                confirmed.set(seq); while(confirmed.get(base)) base++;
            }
            if(kind.equals("ACCEPT_DATA") || kind.equals("CONFIRM_DATA")) {
                require(seq>=0 && seq<chunks && unique.add(seq),"Repeated/out-of-range useful bytes");
                long expected=Math.min(chunk,integer(summary,"file_size_bytes")-(long)seq*chunk);
                require(size==expected,"Wrong useful chunk size"); payload+=size;
            }
            if(kind.equals("RTT_SAMPLE")) {
                require(type.equals("ACK") && rttSequences.add(seq),"Repeated or non-DATA RTT sample");
                require(sends.containsKey(seq) && sends.get(seq)==1,"Ambiguous RTT after retransmission");
                rtts.add(Double.parseDouble(e.get("detail")));
            }
        }
        String[][] counters={{"packets_attempted","SEND"},{"packets_emitted","EMIT"},{"packets_acknowledged","ACKNOWLEDGED"},
            {"timeouts","TIMEOUT"},{"duplicates","DUPLICATE"},{"retransmissions","RETRANSMIT"},{"shim_drops","DROP"},
            {"shim_corruptions","CORRUPT"},{"invalid_packets","RECEIVE_INVALID"},{"data_attempts","SEND_DATA"},
            {"data_retransmissions","RETRANSMIT_DATA"},{"duplicate_data","DUPLICATE_DATA"},{"duplicate_acks","DUPLICATE_ACK"},
            {"dropped_data","DROP_DATA"},{"dropped_acks","DROP_ACK"},{"out_of_order_data","OUT_OF_ORDER"},{"cancelled_packets","CANCEL"}};
        for(String[] pair:counters) equal(summary,pair[0],get(counts,pair[1]));
        equal(summary,"duplicate_control",get(counts,"DUPLICATE")-get(counts,"DUPLICATE_DATA")-get(counts,"DUPLICATE_ACK"));
        equal(summary,"dropped_control",get(counts,"DROP")-get(counts,"DROP_DATA")-get(counts,"DROP_ACK"));
        equal(summary,"packets_received",get(counts,"RECEIVE")+get(counts,"RECEIVE_INVALID"));
        equal(summary,"attempted_udp_bytes",get(bytes,"SEND")); equal(summary,"emitted_udp_bytes",get(bytes,"EMIT"));
        equal(summary,"received_udp_bytes",get(bytes,"RECEIVE")+get(bytes,"RECEIVE_INVALID"));
        equal(summary,"cancelled_udp_bytes",get(bytes,"CANCEL")); equal(summary,"payload_bytes",payload);
        require(get(counts,"SEND")==get(counts,"EMIT")+get(counts,"DROP")+get(counts,"CANCEL")+get(counts,"SEND_ERROR"),"Unaccounted send attempt");
        equal(summary,"rtt_samples",rtts.size());
        if(role.equals("sender")) {
            int eligible=0; for(int count:sends.values()) if(count==1) eligible++;
            require(eligible==rtts.size(),"Missing eligible RTT samples");
        }
        double seconds=Double.parseDouble(summary.get("elapsed_seconds"));
        require(seconds>0,"Nonpositive transfer duration");
        double rate=payload*8.0/seconds/1e6;
        near(summary,"goodput_mbps",rate,0.000001+rate*0.00000051/seconds);
        near(summary,"retransmission_ratio",get(counts,"SEND")==0?0:get(counts,"RETRANSMIT")/(double)get(counts,"SEND"),0.000001);
        if(rtts.isEmpty()) require("NA".equals(summary.get("rtt_mean_ms")) && "NA".equals(summary.get("rtt_p95_ms")),"RTT should be unavailable");
        else {
            double sum=0,variation=0;
            for(int i=0;i<rtts.size();i++) { sum+=rtts.get(i); if(i>0) variation+=Math.abs(rtts.get(i)-rtts.get(i-1)); }
            near(summary,"rtt_mean_ms",sum/rtts.size(),0.000001);
            if(rtts.size()>1) near(summary,"rtt_variation_ms",variation/(rtts.size()-1),0.000001);
            else require("NA".equals(summary.get("rtt_variation_ms")),"Variation needs more samples");
            Collections.sort(rtts); near(summary,"rtt_p95_ms",rtts.get((int)Math.ceil(.95*rtts.size())-1),0.000001);
        }
        return summary;
    }

    static void require(boolean condition,String message) throws IOException { if(!condition) throw new IOException("Evidence audit: "+message); }
    static long integer(Map<String,String> row,String key) { return Long.parseLong(row.get(key)); }
    static long get(Map<String,Long> map,String key) { return map.containsKey(key)?map.get(key):0; }
    static void add(Map<String,Long> map,String key,long value) { map.put(key,get(map,key)+value); }
    static void equal(Map<String,String> row,String key,long value) throws IOException {
        require(integer(row,key)==value,"Counter "+key+": saved="+row.get(key)+", recomputed="+value);
    }
    static void near(Map<String,String> row,String key,double value,double tolerance) throws IOException {
        double saved=Double.parseDouble(row.get(key));
        require(Double.isFinite(saved) && Math.abs(saved-value)<=tolerance,"Metric "+key+": saved="+saved+", recomputed="+value);
    }
    static Map<String,String> one(Path path) throws IOException {
        List<Map<String,String>> rows=readCsv(path); require(rows.size()==1,"Expected one summary row in "+path); return rows.get(0);
    }
    static List<Map<String,String>> readCsv(Path path) throws IOException {
        List<Map<String,String>> result=new ArrayList<Map<String,String>>();
        try(BufferedReader in=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            String first=in.readLine(); require(first!=null,"Empty CSV: "+path);
            List<String> keys=csvLine(first); String line;
            while((line=in.readLine())!=null) {
                List<String> values=csvLine(line); require(values.size()==keys.size(),"CSV width mismatch: "+path);
                Map<String,String> row=new LinkedHashMap<String,String>();
                for(int i=0;i<keys.size();i++) row.put(keys.get(i),values.get(i));
                result.add(row);
            }
        }
        return result;
    }
    static List<String> csvLine(String line) throws IOException {
        List<String> cells=new ArrayList<String>(); StringBuilder cell=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') {
                if(quoted && i+1<line.length() && line.charAt(i+1)=='"') { cell.append('"'); i++; }
                else quoted=!quoted;
            } else if(c==',' && !quoted) { cells.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        require(!quoted,"Unterminated CSV quote"); cells.add(cell.toString()); return cells;
    }
}

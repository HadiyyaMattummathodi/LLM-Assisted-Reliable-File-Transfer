package acn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Command-line entry point. No external libraries; optional local Ollama interface. */
public final class Main {
    public static void main(String[] args) {
        try {
            String action=args.length==0?"help":args[0];
            if(action.equals("chat")) ChatConsole.start();
            else if(action.equals("interface-test")) InterfaceTests.run();
            else if(action.equals("files")) makeFiles();
            else if(action.equals("demo")) {
                makeFiles();
                String scenario=args.length>1?args[1]:"baseline";
                String name=args.length>2?args[2]:"small.bin";
                run(name,Config.scenario(scenario),Config.scenario(scenario),"demo-"+scenario);
            } else if(action.equals("receive")) {
                String scenario=args.length>1?args[1]:"baseline";
                Path folder=newRun("receive-"+scenario);
                System.out.println("Receiver logs: "+folder);
                Transfer.Receiver receiver=new Transfer.Receiver(Config.scenario(scenario),folder,Paths.get("received"));
                receiver.run(); if(receiver.failure!=null) throw receiver.failure;
            } else if(action.equals("send")) {
                String filename=args.length>1?args[1]:"small.bin",scenario=args.length>2?args[2]:"baseline";
                Path folder=newRun("send-"+scenario);
                new Transfer.Sender(Config.scenario(scenario),folder).send(filename);
                System.out.println("Sender logs: "+folder);
            } else if(action.equals("experiments")) Experiments.run(args.length>1?Integer.parseInt(args[1]):5);
            else if(action.equals("selftest")) selftest();
            else if(action.equals("protocol-test")) ProtocolTests.run();
            else if(action.equals("audit")) EvidenceAudit.run(args.length>1?Paths.get(args[1]):Paths.get("runs"));
            else help();
        } catch(Exception e) {
            System.err.println("FAILED: "+e.getMessage());
            if(e instanceof java.net.BindException)
                System.err.println("Port 9000 is busy. Stop the other receiver or chat transfer, then retry.");
            System.exit(1);
        }
    }

    static void help() {
        System.out.println("Java 8 reliable UDP transfer with a local LLM interface");
        System.out.println("java -cp out acn.Main chat");
        System.out.println("java -cp out acn.Main interface-test");
        System.out.println("java -cp out acn.Main files");
        System.out.println("java -cp out acn.Main demo [baseline|lossy|delayed|delayed-short|delayed-narrow|corrupt] [filename]");
        System.out.println("java -cp out acn.Main receive [scenario]");
        System.out.println("java -cp out acn.Main send [filename] [scenario]");
        System.out.println("java -cp out acn.Main experiments [repetitions: default 5]");
        System.out.println("java -cp out acn.Main selftest");
        System.out.println("java -cp out acn.Main protocol-test");
        System.out.println("java -cp out acn.Main audit [experiment folder]");
        System.out.println("Only simple filenames inside inputs are accepted. Receiver A is fixed at 127.0.0.1:9000.");
    }

    static Path newRun(String label) throws IOException {
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path path=Paths.get("runs",label+"-"+stamp+"-"+UUID.randomUUID().toString().substring(0,8));
        Files.createDirectories(path); return path;
    }
    static void makeFiles() throws IOException {
        Files.createDirectories(Paths.get("inputs"));
        sample("small.bin",32*1024,11); sample("large.bin",1024*1024,12);
        sample("empty.bin",0,13); sample("partial.bin",2345,14);
        Path text=Paths.get("inputs","hello.txt");
        if(!Files.exists(text)) Files.write(text,"Hello Hadiyya! This file travelled over UDP.\r\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("Input files ready: small.bin (32 KiB), large.bin (1 MiB), hello.txt, empty.bin, partial.bin");
    }
    static void sample(String name,int bytes,long seed) throws IOException {
        Path path=Paths.get("inputs",name);
        if(!Files.exists(path)) { byte[] data=new byte[bytes]; new Random(seed).nextBytes(data); Files.write(path,data,StandardOpenOption.CREATE_NEW); }
    }

    static Map<String,String> run(String filename,Config senderConfig,Config receiverConfig,String label) throws Exception {
        return run(filename,senderConfig,receiverConfig,label,null);
    }

    static Map<String,String> run(String filename,Config senderConfig,Config receiverConfig,String label,java.util.function.Consumer<Transfer.Sender> observer) throws Exception {
        // Validate before opening the receiver or creating a transfer.
        Config.input(filename); senderConfig.validate(); receiverConfig.validate();
        Path folder=newRun(label);
        return runInFolder(filename,senderConfig,receiverConfig,folder,observer);
    }

    static Map<String,String> runInFolder(String filename,Config senderConfig,Config receiverConfig,Path folder,
            java.util.function.Consumer<Transfer.Sender> observer) throws Exception {
        Config.input(filename); senderConfig.validate(); receiverConfig.validate();
        Transfer.Receiver receiver=new Transfer.Receiver(receiverConfig,folder,Paths.get("received"));
        Thread thread=new Thread(receiver,"udp-receiver"); thread.start();
        Transfer.Sender sender=new Transfer.Sender(senderConfig,folder);
        if(observer!=null) observer.accept(sender);
        Map<String,String> row;
        try { row=sender.send(filename); }
        finally { receiver.close(); thread.join(5000); }
        if(thread.isAlive()) throw new IOException("Receiver did not shut down");
        if(receiver.failure!=null) throw receiver.failure;
        if(receiver.result==null) throw new IOException("No verified file at receiver");
        byte[] source=Files.readAllBytes(Config.input(filename));
        byte[] actual=Files.readAllBytes(receiver.result);
        if(!Arrays.equals(source,actual)) throw new IOException("Independent byte comparison failed");
        if(sender.metrics.payloadBytes!=source.length || receiver.metrics.payloadBytes!=source.length)
            throw new IOException("Unique-byte accounting mismatch");
        row.put("receiver_state",receiver.metrics.state);
        row.put("receiver_payload_bytes",""+receiver.metrics.payloadBytes);
        row.put("receiver_duplicates",""+receiver.metrics.duplicates);
        row.put("receiver_invalid_packets",""+receiver.metrics.invalid);
        row.put("receiver_shim_drops",""+receiver.metrics.drops);
        row.put("receiver_attempts",""+receiver.metrics.attempts);
        row.put("receiver_emitted",""+receiver.metrics.emitted);
        row.put("receiver_received",""+receiver.metrics.received);
        row.put("receiver_duplicate_data",""+receiver.metrics.dataDuplicates);
        row.put("receiver_duplicate_control",""+receiver.metrics.controlDuplicates);
        row.put("receiver_dropped_acks",""+receiver.metrics.ackDrops);
        row.put("receiver_dropped_control",""+receiver.metrics.controlDrops);
        row.put("receiver_out_of_order_data",""+receiver.metrics.outOfOrder);
        long attempted=sender.metrics.attemptedBytes+receiver.metrics.attemptedBytes;
        long emitted=sender.metrics.emittedBytes+receiver.metrics.emittedBytes;
        row.put("both_directions_attempted_udp_bytes",""+attempted);
        row.put("both_directions_emitted_udp_bytes",""+emitted);
        row.put("attempted_overhead_share",Metrics.number(attempted==0?0:(attempted-source.length)/(double)attempted));
        row.put("emitted_overhead_share",Metrics.number(emitted==0?0:(emitted-source.length)/(double)emitted));
        row.put("bytes_equal","true"); row.put("received_file",receiver.result.toString()); row.put("run_folder",folder.toString());
        Metrics.writeCsv(folder.resolve("summary.csv"),Collections.singletonList(row));
        System.out.println("Goodput: "+row.get("goodput_mbps")+" Mbps; retransmissions: "+row.get("retransmissions")+
            "; intentional drops: "+(sender.metrics.drops+receiver.metrics.drops));
        System.out.println("Measurements: "+folder.resolve("summary.csv"));
        return row;
    }

    static void selftest() throws Exception {
        makeFiles(); int tests=0;
        // Every handshake/data/completion exchange must survive a single lost message.
        for(int type:new int[]{Packet.START,Packet.READY,Packet.DATA,Packet.ACK,Packet.FIN,Packet.DONE}) {
            Config a=new Config(),b=new Config(); a.dropFirstType=type; b.dropFirstType=type;
            Map<String,String> r=run("partial.bin",a,b,"test-drop-"+Packet.name(type));
            require(Long.parseLong(r.get("retransmissions"))>=1,"Missing expected retransmission"); tests++;
        }
        Config corrupt=new Config(); corrupt.corruptFirstType=Packet.DATA;
        Map<String,String> crc=run("partial.bin",corrupt,new Config(),"test-corrupt-data");
        require(Long.parseLong(crc.get("receiver_invalid_packets"))>=1,"CRC did not detect corruption"); tests++;
        run("empty.bin",new Config(),new Config(),"test-empty"); tests++;
        Config negotiated=new Config(); negotiated.chunk=1200;
        Map<String,String> nr=run("partial.bin",negotiated,new Config(),"test-negotiate");
        require(nr.get("chunk_bytes").equals("1024"),"Chunk negotiation failed"); tests++;
        run("small.bin",Config.scenario("delayed-short"),Config.scenario("delayed-short"),"test-reorder-timeouts"); tests++;
        try { Config.input("../private.txt"); throw new AssertionError("Unsafe path accepted"); }
        catch(IllegalArgumentException expected) { tests++; }
        Config bad=new Config(); bad.windowBytes=1000000;
        try { bad.validate(); throw new AssertionError("Unsafe window accepted"); }
        catch(IllegalArgumentException expected) { tests++; }
        // A permanently lost START must terminate, not wait forever.
        Config lost=new Config(); lost.loss=1; lost.maxRetries=1; lost.timeoutMs=20;
        boolean failed=false;
        try { run("partial.bin",lost,new Config(),"test-total-loss"); }
        catch(IOException expected) { failed=true; }
        require(failed,"Total loss did not fail within retry budget"); tests++;
        System.out.println("PASS: "+tests+" checks; successful files compared byte for byte.");
    }
    static void require(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
}

package acn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Repeated, paired-seed experiments. A failed trial stays in the evidence. */
final class Experiments {
    static final class Case {
        final String id, file, scenario;
        final int window;
        Case(String id,String file,String scenario,int window) {
            this.id=id; this.file=file; this.scenario=scenario; this.window=window;
        }
        Config config(long seed) {
            Config c=Config.scenario(scenario);
            if(window!=0) c.windowBytes=window;
            c.seed=seed; c.validate(); return c;
        }
    }

    static List<Case> cases() {
        List<Case> cases=new ArrayList<Case>();
        for(String scenario:new String[]{"baseline","lossy","delayed"})
            for(String file:new String[]{"small.bin","large.bin"})
                cases.add(new Case(scenario+"-"+file,file,scenario,0));
        cases.add(new Case("short-timeout-small","small.bin","delayed-short",0));
        cases.add(new Case("short-timeout-large","large.bin","delayed-short",0));
        cases.add(new Case("narrow-window-small","small.bin","delayed",1024));
        cases.add(new Case("wide-window-large","large.bin","delayed",65536));
        return cases;
    }

    static void run(int repetitions) throws Exception {
        if(repetitions<1 || repetitions>20) throw new IllegalArgumentException("Repetitions must be 1..20");
        Main.makeFiles();
        checkInput("small.bin",32*1024,11); checkInput("large.bin",1024*1024,12);
        Path batch=Main.newRun("experiments-v7");
        System.out.println("EXPERIMENT BATCH: "+batch);
        System.out.println("One excluded warm-up, then "+(10*repetitions)+" measured transfers. Do not run another receiver/chat concurrently.");
        if(repetitions<3) System.out.println("SMOKE RUN ONLY: use the default five repetitions for evaluation evidence.");
        List<Map<String,String>> plan=new ArrayList<Map<String,String>>();
        Map<String,Case> definitions=new LinkedHashMap<String,Case>();
        for(Case c:cases()) definitions.put(c.id,c);
        int order=0;
        for(int replicate=1;replicate<=repetitions;replicate++) {
            List<Case> shuffled=cases(); Collections.shuffle(shuffled,new Random(42000L+replicate));
            for(Case c:shuffled) {
                Config config=c.config(41L+replicate);
                Map<String,String> item=new LinkedHashMap<String,String>();
                item.put("case_id",c.id); item.put("replicate",""+replicate); item.put("run_order",""+(++order));
                item.put("seed",""+config.seed); item.put("filename",c.file); item.put("scenario",config.scenario);
                item.put("window_bytes",""+config.windowBytes); item.put("timeout_ms",""+config.timeoutMs);
                item.put("loss_probability",""+config.loss); item.put("one_way_base_delay_ms",""+config.delayMs);
                item.put("one_way_jitter_range_ms",""+config.jitterMs);
                item.put("trial_folder",String.format(Locale.US,"trial-%02d-%s",order,c.id));
                plan.add(item);
            }
        }
        Metrics.writeCsv(batch.resolve("plan.csv"),plan);
        Map<String,Object> manifest=Json.object("format_version",7,"started_utc",Instant.now().toString(),
            "status","RUNNING","repetitions",repetitions,"planned_trials",plan.size(),"finished_trials",0,
            "successful_trials",0,"failed_trials",0,"warmup","warmup-baseline-large (excluded)",
            "java_version",System.getProperty("java.version"),"os",System.getProperty("os.name"),
            "os_version",System.getProperty("os.version"),"architecture",System.getProperty("os.arch"),
            "processors",Runtime.getRuntime().availableProcessors(),"max_heap_bytes",Runtime.getRuntime().maxMemory(),
            "sender_seeds","42 through "+(41+repetitions),"receiver_seed_offset",1000003,
            "order_seeds","42001 through "+(42000+repetitions),"source_sha256",sourceHashes(),
            "input_sha256",Json.object("small.bin",Transfer.hex(Transfer.digest(Files.readAllBytes(Config.input("small.bin")))),
                "large.bin",Transfer.hex(Transfer.digest(Files.readAllBytes(Config.input("large.bin"))))));
        saveManifest(batch,manifest);
        try {
            Main.runInFolder("large.bin",new Config(),new Config(),batch.resolve("warmup-baseline-large"),null);
        } catch(Exception e) {
            manifest.put("status","WARMUP_FAILED"); manifest.put("error",e.toString()); saveManifest(batch,manifest); throw e;
        }
        List<Map<String,String>> results=new ArrayList<Map<String,String>>();
        int failed=0;
        for(Map<String,String> item:plan) {
            Case c=definitions.get(item.get("case_id"));
            long seed=Long.parseLong(item.get("seed"));
            Path folder=batch.resolve(item.get("trial_folder"));
            Map<String,String> row=new LinkedHashMap<String,String>(item);
            System.out.println("Trial "+item.get("run_order")+"/"+plan.size()+": "+c.id+", seed "+seed);
            try {
                row.putAll(Main.runInFolder(c.file,c.config(seed),c.config(seed),folder,null));
                row.put("trial_status","COMPLETED"); row.put("error","");
            } catch(Exception e) {
                failed++; row.put("trial_status","FAILED"); row.put("error",e.toString());
                row.put("run_folder",folder.toString());
                System.err.println("Trial failed and retained: "+e.getMessage());
            }
            results.add(row);
            Metrics.writeCsv(batch.resolve("all-results.csv"),results);
            manifest.put("finished_trials",results.size()); manifest.put("failed_trials",failed);
            manifest.put("successful_trials",results.size()-failed); saveManifest(batch,manifest);
        }
        aggregate(batch,results,repetitions);
        manifest.put("status",failed==0?"COMPLETED":"COMPLETED_WITH_FAILURES");
        manifest.put("finished_utc",Instant.now().toString()); saveManifest(batch,manifest);
        System.out.println("RESULTS: "+batch.resolve("all-results.csv"));
        System.out.println("AGGREGATES: "+batch.resolve("aggregate.csv"));
        if(failed!=0) throw new IOException(failed+" experiment trials failed; inspect the retained evidence before reporting results");
        EvidenceAudit.run(batch);
        System.out.println("ALL "+plan.size()+" EXPERIMENT TRIALS COMPLETE AND AUDITED: "+batch);
    }

    static void checkInput(String name,int size,long seed) throws IOException {
        byte[] expected=new byte[size]; new Random(seed).nextBytes(expected);
        if(!Arrays.equals(expected,Files.readAllBytes(Config.input(name))))
            throw new IOException(name+" differs from the documented test fixture. Rename it as a backup and run again to generate the standard input.");
    }

    static Map<String,Object> sourceHashes() throws IOException {
        Map<String,Object> hashes=new LinkedHashMap<String,Object>();
        try(Stream<Path> paths=Files.walk(Paths.get("src"))) {
            Iterator<Path> it=paths.filter(p -> p.toString().endsWith(".java")).sorted().iterator();
            while(it.hasNext()) { Path p=it.next(); hashes.put(p.toString().replace('\\','/'),Transfer.hex(Transfer.digest(Files.readAllBytes(p)))); }
        }
        return hashes;
    }

    static void saveManifest(Path batch,Map<String,Object> manifest) throws IOException {
        Path target=batch.resolve("manifest.json"),temp=batch.resolve("manifest.json.tmp");
        Files.write(temp,(Json.write(manifest)+"\n").getBytes(StandardCharsets.UTF_8));
        try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
    }

    static void aggregate(Path batch,List<Map<String,String>> results,int repetitions) throws IOException {
        String[] fields={"goodput_mbps","elapsed_seconds","emitted_overhead_share","retransmissions",
            "retransmission_ratio","timeouts","rtt_mean_ms","rtt_p95_ms","rtt_variation_ms",
            "shim_drops","receiver_shim_drops","receiver_duplicate_data","receiver_out_of_order_data"};
        List<Map<String,String>> rows=new ArrayList<Map<String,String>>();
        for(Case c:cases()) {
            List<Map<String,String>> good=new ArrayList<Map<String,String>>();
            for(Map<String,String> r:results)
                if(c.id.equals(r.get("case_id")) && "COMPLETED".equals(r.get("trial_status"))) good.add(r);
            Map<String,String> row=new LinkedHashMap<String,String>();
            row.put("case_id",c.id); row.put("planned_trials",""+repetitions);
            row.put("successful_trials",""+good.size()); row.put("failed_trials",""+(repetitions-good.size()));
            for(String field:fields) {
                List<Double> values=new ArrayList<Double>();
                for(Map<String,String> r:good) {
                    String value=r.get(field);
                    if(value!=null && !value.equals("NA")) values.add(Double.parseDouble(value));
                }
                row.put(field+"_n",""+values.size());
                double sum=0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
                for(double v:values) { sum+=v; min=Math.min(min,v); max=Math.max(max,v); }
                double mean=values.isEmpty()?0:sum/values.size(), squares=0;
                for(double v:values) squares+=(v-mean)*(v-mean);
                row.put(field+"_mean",values.isEmpty()?"NA":Metrics.number(mean));
                row.put(field+"_sd",values.size()<2?"NA":Metrics.number(Math.sqrt(squares/(values.size()-1))));
                row.put(field+"_min",values.isEmpty()?"NA":Metrics.number(min));
                row.put(field+"_max",values.isEmpty()?"NA":Metrics.number(max));
            }
            rows.add(row);
        }
        Metrics.writeCsv(batch.resolve("aggregate.csv"),rows);
    }
}

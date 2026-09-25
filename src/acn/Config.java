package acn;

import java.nio.file.*;
import java.io.IOException;

/** Trusted configuration; future LLM commands must pass these checks too. */
final class Config {
    static final int PORT=9000, MAX_FILE=16*1024*1024;
    String scenario="baseline";
    int chunk=1024, windowBytes=32768, timeoutMs=250, maxRetries=20;
    double loss=0, corruption=0;
    int delayMs=0, jitterMs=0;
    long seed=42;
    // Deterministic faults are used only by selftest.
    int dropFirstType=0, corruptFirstType=0;

    static Config scenario(String name) {
        Config c=new Config(); c.scenario=name;
        if(name.equals("lossy")) c.loss=0.03;
        else if(name.equals("delayed") || name.equals("delayed-short") || name.equals("delayed-narrow")) {
            c.delayMs=25; c.jitterMs=25;
            if(name.equals("delayed-short")) c.timeoutMs=30;
            if(name.equals("delayed-narrow")) c.windowBytes=1024;
        } else if(name.equals("corrupt")) c.corruption=0.02;
        else if(!name.equals("baseline")) throw new IllegalArgumentException("Unknown scenario: "+name);
        c.validate(); return c;
    }
    void validate() {
        if(chunk<256 || chunk>1200) throw new IllegalArgumentException("Chunk must be 256..1200 bytes");
        if(windowBytes<chunk || windowBytes>65536) throw new IllegalArgumentException("Window must be chunk size..65536 bytes");
        if(timeoutMs<20 || timeoutMs>5000) throw new IllegalArgumentException("Timeout must be 20..5000 ms");
        if(maxRetries<1 || maxRetries>20) throw new IllegalArgumentException("Retries must be 1..20");
        if(!Double.isFinite(loss) || loss<0 || loss>1 || !Double.isFinite(corruption) || corruption<0 || corruption>1)
            throw new IllegalArgumentException("Invalid impairment probability");
        if(delayMs<0 || jitterMs<0 || (long)delayMs+jitterMs>2000) throw new IllegalArgumentException("Invalid delay");
    }
    static void validateName(String name) {
        if(name==null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))
            throw new IllegalArgumentException("Use a simple filename inside inputs; paths are not allowed");
    }
    static Path input(String name) throws IOException {
        validateName(name);
        Path root=Paths.get("inputs").toRealPath();
        Path file=root.resolve(name).toRealPath();
        if(!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file)>MAX_FILE)
            throw new IllegalArgumentException("File must be in inputs and at most 16 MiB");
        return file;
    }
    int packetsInWindow() { return Math.max(1,windowBytes/chunk); }
}

package acn;

import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec. Rejects duplicate keys, trailing text and deep nesting. */
final class Json {
    static Map<String,Object> object(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]); return m;
    }
    static Object parse(String text) {
        if(text==null || text.length()>262144) throw new IllegalArgumentException("JSON size limit");
        Parser p=new Parser(text); Object value=p.value(0); p.space();
        if(p.at!=text.length()) throw p.error("Trailing JSON text"); return value;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> map(Object value) {
        if(!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>)value;
    }
    static String string(Object value) {
        if(!(value instanceof String)) throw new IllegalArgumentException("Expected JSON string"); return (String)value;
    }
    static int integer(Object value) {
        if(!(value instanceof BigDecimal)) throw new IllegalArgumentException("Expected JSON integer");
        try { return ((BigDecimal)value).intValueExact(); }
        catch(ArithmeticException e) { throw new IllegalArgumentException("Expected bounded integer"); }
    }
    static String write(Object value) {
        if(value==null) return "null";
        if(value instanceof String) {
            StringBuilder s=new StringBuilder("\"");
            for(char c:((String)value).toCharArray()) {
                if(c=='"' || c=='\\') s.append('\\').append(c);
                else if(c<32 || Character.isSurrogate(c)) s.append(String.format(Locale.US,"\\u%04x",(int)c));
                else s.append(c);
            }
            return s.append('"').toString();
        }
        if(value instanceof Boolean || value instanceof Number) {
            String s=value.toString();
            if(s.equals("NaN") || s.contains("Infinity")) throw new IllegalArgumentException("Non-finite number");
            return s;
        }
        if(value instanceof Map) {
            List<String> parts=new ArrayList<String>();
            for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()) parts.add(write(string(e.getKey()))+":"+write(e.getValue()));
            return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof Iterable) {
            List<String> parts=new ArrayList<String>(); for(Object x:(Iterable<?>)value) parts.add(write(x));
            return "["+String.join(",",parts)+"]";
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    static final class Parser {
        final String text; int at;
        Parser(String text) { this.text=text; }
        IllegalArgumentException error(String why) { return new IllegalArgumentException(why+" at JSON offset "+at); }
        void space() { while(at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0) at++; }
        boolean take(char c) { space(); if(at<text.length() && text.charAt(at)==c) {at++;return true;} return false; }
        void need(char c) { if(!take(c)) throw error("Expected "+c); }
        Object value(int depth) {
            if(depth>24) throw error("JSON nesting limit"); space();
            if(at>=text.length()) throw error("Missing value");
            char c=text.charAt(at);
            if(c=='"') return quoted();
            if(c=='{') {
                at++; Map<String,Object> result=new LinkedHashMap<String,Object>();
                if(take('}')) return result;
                do {
                    space(); if(at>=text.length() || text.charAt(at)!='"') throw error("Expected object key");
                    String key=quoted(); need(':');
                    if(result.containsKey(key)) throw error("Duplicate key");
                    result.put(key,value(depth+1));
                } while(take(',')); need('}'); return result;
            }
            if(c=='[') {
                at++; List<Object> result=new ArrayList<Object>(); if(take(']')) return result;
                do { result.add(value(depth+1)); } while(take(',')); need(']'); return result;
            }
            if(text.startsWith("true",at)) {at+=4;return Boolean.TRUE;}
            if(text.startsWith("false",at)) {at+=5;return Boolean.FALSE;}
            if(text.startsWith("null",at)) {at+=4;return null;}
            int start=at; if(c=='-') at++;
            if(at>=text.length()) throw error("Invalid number");
            if(text.charAt(at)=='0') at++;
            else { if(!digit19(text.charAt(at))) throw error("Invalid value"); while(at<text.length() && digit(text.charAt(at))) at++; }
            if(at<text.length() && text.charAt(at)=='.') { at++; digits(); }
            if(at<text.length() && (text.charAt(at)=='e' || text.charAt(at)=='E')) {
                at++; if(at<text.length() && (text.charAt(at)=='+' || text.charAt(at)=='-')) at++; digits();
            }
            if(at-start>128) throw error("Number too long");
            try { return new BigDecimal(text.substring(start,at)); }
            catch(NumberFormatException e) { throw error("Invalid number"); }
        }
        boolean digit(char c) { return c>='0' && c<='9'; }
        boolean digit19(char c) { return c>='1' && c<='9'; }
        void digits() { int start=at; while(at<text.length() && digit(text.charAt(at))) at++; if(at==start) throw error("Expected digit"); }
        String quoted() {
            at++; StringBuilder s=new StringBuilder();
            while(at<text.length()) {
                char c=text.charAt(at++);
                if(c=='"') return s.toString();
                if(c<32) throw error("Unescaped control character");
                if(c=='\\') {
                    if(at>=text.length()) throw error("Incomplete escape"); c=text.charAt(at++);
                    if(c=='"' || c=='\\' || c=='/') s.append(c);
                    else if(c=='b') s.append('\b'); else if(c=='f') s.append('\f');
                    else if(c=='n') s.append('\n'); else if(c=='r') s.append('\r'); else if(c=='t') s.append('\t');
                    else if(c=='u') {
                        if(at+4>text.length()) throw error("Incomplete Unicode escape");
                        String hex=text.substring(at,at+4);
                        if(!hex.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                        s.append((char)Integer.parseInt(hex,16)); at+=4;
                    } else throw error("Invalid escape");
                } else s.append(c);
            }
            throw error("Unclosed string");
        }
    }
}

"""Optional HTTP test double, not a live-model test. Stop Ollama before running.
Run from the project root: python tests/http_adapter_test.py
Requires Python 3; the Java-only verify.cmd does not require Python.
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json, os, subprocess, threading
os.chdir(Path(__file__).resolve().parents[1])
calls=[]
analysis_calls=[]
attempts={}
frozen={}
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.path=='/api/chat'
        assert request['model']=='qwen2.5:1.5b' and request['stream'] is False
        assert 'tools' not in request
        assert request['format'].get('additionalProperties') is False or all(branch.get('additionalProperties') is False for branch in request['format']['anyOf'])
        system=request['messages'][0]['content']; question=request['messages'][1]['content']
        calls.append(question)
        reason='stop'
        if 'Available evidence: ' in system:
            evidence=json.loads(system.split('Available evidence: ',1)[1])
            assert evidence['payload_bytes']=='46' and evidence['state']=='COMPLETED'
            assert request['options']['num_predict']==1024
            analysis_calls.append((question,evidence))
            scenario=question.splitlines()[0]
            attempts[scenario]=attempts.get(scenario,0)+1
            if scenario in frozen:
                assert evidence==frozen[scenario]
                assert 'The last response failed' in question
            frozen[scenario]=evidence
            schema=request['format']['properties']['observations']
            assert schema['minItems']==2 and schema['maxItems']==3
            item=schema['items']['properties']
            assert 'payload_bytes' in item['field']['enum']
            assert 'state' not in item['field']['enum']
            assert 'pattern' not in item['comment']
            assert item['comment']['minLength']==20 and item['comment']['maxLength']==240
            result={'observations':[{'field':'payload_bytes','comment':'The receiver acknowledged 46 bytes.'},
                                    {'field':'retransmissions','comment':'These are repeat send attempts.'}]}
            if 'invalid' in scenario or ('repair' in scenario and attempts[scenario]==1):
                result['observations'][0]['comment']='Delivered 999 bytes.'
            if 'blank' in scenario and attempts[scenario]==1:
                result['observations'][0]['comment']='The packets received are     .'
            if 'persistent' in scenario or ('cutoff' in scenario and attempts[scenario]==1):
                reason='length'
            raw=json.dumps(result)
            if 'persistent' in scenario: raw='{"observations":[{"field":"payload_bytes"'
        else:
            assert request['options']['num_predict']==512
            action='send' if question.startswith('Send') else 'explain' if question.startswith('Explain') else 'status'
            result={'action':action,'file':'','receiver':'','scenario':'','window_bytes':0,'timeout_ms':0}
            if action=='send':
                result.update(file='../private.txt' if 'private' in question else 'hello.txt',receiver='A',scenario='baseline',window_bytes=32768,timeout_ms=250)
            raw=json.dumps(result)
        response=json.dumps({'done':True,'done_reason':reason,'message':{'role':'assistant','content':raw}}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(response))); self.end_headers(); self.wfile.write(response)
server=HTTPServer(('127.0.0.1',11434),Handler)
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
try:
    commands=['Send hello.txt to receiver A',':wait','How much has been delivered?',
              'Explain complete','Explain repair','Explain blank','Explain cutoff','Explain persistent','Explain invalid',
              'How much has been delivered?','Send ../private.txt to receiver A',':unsafe',':quit']
    r=subprocess.run(['java','-cp','out','acn.Main','chat'],input='\n'.join(commands)+'\n',text=True,capture_output=True,timeout=20)
    Path('runs/http-adapter-test-output.txt').write_text(r.stdout+r.stderr,encoding='utf-8')
    assert r.returncode==0,(r.stdout,r.stderr)
    assert 'SUCCESS: hello.txt | 46 bytes | SHA-256 verified' in r.stdout
    assert '46 [payload_bytes]' in r.stdout and '0 [retransmissions]' in r.stdout
    assert r.stdout.count('REJECTED / NOT EXECUTED:')==2,r.stdout
    assert len(calls)==21,calls
    assert len(analysis_calls)==11,analysis_calls
    assert all(n<=2 for n in attempts.values()),attempts
    assert r.stdout.count('Explanation incomplete or invalid;')==5,r.stdout
    assert r.stdout.count('EXPLANATION REJECTED:')==2,r.stdout
    assert r.stdout.count('LLM explanation with verified measurement references:')==4,r.stdout
    logpath=Path(next(line.split(': ',1)[1] for line in r.stdout.splitlines() if line.startswith('Conversation log: ')))
    events=[json.loads(line) for line in logpath.read_text().splitlines()]
    assert sum(e['kind']=='model_analysis_raw' for e in events)==11
    assert sum(e['kind']=='analysis_rejection' for e in events)==7
    assert sum(e['kind']=='model_analysis_incomplete' for e in events)==3
    assert sum(e['kind']=='grounded_analysis' for e in events)==4
    assert sum(e['kind']=='transfer_complete' for e in events)==1
    snapshots=[e['detail'] for e in events if e['kind']=='metrics_snapshot']
    assert snapshots[-1]['state']=='COMPLETED' and snapshots[-1]['payload_bytes']=='46'
    print('PASS: HTTP adapter, real UDP transfer, status, grounded observations, incorrect-number, unfinished-sentence and token-limit recovery, bounded failures, unchanged measurements, complete audit records and unsafe-command rejection.')
    print('The HTTP server used deterministic test responses; this does not test a real model.')
finally:
    server.shutdown(); server.server_close();thread.join()

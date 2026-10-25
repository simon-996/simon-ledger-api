#!/usr/bin/env python3
"""Exercise deployment preflight/readiness/rollback using local fake Docker and curl."""
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('deploy-server.sh')
FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p=Path(os.environ['FAKE_STATE']); state=json.loads(p.read_text()); args=sys.argv[1:]
state['calls'].append(args)
mode=os.environ['SCENARIO']; code=0; output=''
if args[0]=='container':
 name=args[-1]
 if name not in state['containers']: code=1
 elif '-f' in args: output=str(state['containers'][name]).lower()
elif args[0]=='build' and mode=='build-fail': code=1
elif args[0]=='stop': state['containers'][args[1]]=False
elif args[0]=='rename': state['containers'][args[2]]=state['containers'].pop(args[1])
elif args[0]=='start': state['containers'][args[1]]=True
elif args[0]=='run':
 if '--rm' in args:
  if mode=='permission-fail': code=1
 elif mode=='run-fail': code=1
 else: state['containers']['review-api']=True
elif args[0]=='rm': state['containers'].pop(args[-1],None)
p.write_text(json.dumps(state)); print(output); sys.exit(code)
'''
FAKE_CURL = '''#!/usr/bin/env python3
import os, sys
if os.environ['SCENARIO']=='health-fail': sys.exit(1)
print('{"code":0,"data":{"status":"up"}}')
'''

class DeployRollbackTests(unittest.TestCase):
    def scenario(self, mode):
        with tempfile.TemporaryDirectory(prefix='ledger-deploy-test-') as folder:
            root = Path(folder); bins = root/'bin'; bins.mkdir()
            for name, source in [('docker', FAKE_DOCKER), ('curl', FAKE_CURL)]:
                target = bins/name; target.write_text(source); target.chmod(0o755)
            state = root/'state.json'; state.write_text(json.dumps({'containers': {'review-api': True}, 'calls': []}))
            app = root/'review'/'api'; app.mkdir(parents=True); (app/'old').write_text('old')
            envfile = root/'api.env'; envfile.write_text('FAKE=1\n')
            bundle = root/'release.tar.gz'
            with tarfile.open(bundle, 'w:gz') as archive:
                archive.add(SCRIPT.parent.parent/'Dockerfile', arcname='Dockerfile')
                jar = root/'app.jar'; jar.write_text('fake'); archive.add(jar, arcname='app.jar')
            env = dict(os.environ, PATH=str(bins)+os.pathsep+os.environ['PATH'], BASE_DIR=str(root), APP_NAME='review',
                       CONTAINER_NAME='review-api', ENV_FILE=str(envfile), PACKAGE_FILE=str(bundle),
                       HEALTH_ATTEMPTS='1', HEALTH_INTERVAL='0', SCENARIO=mode, FAKE_STATE=str(state))
            result = subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True)
            final = json.loads(state.read_text())
            self.assertEqual(mode=='success', result.returncode==0, result.stdout+result.stderr)
            self.assertTrue(final['containers'].get('review-api'), result.stdout)
            calls = final['calls']
            if mode in ('build-fail','permission-fail'): self.assertFalse(any(c[0]=='stop' for c in calls))
            elif mode!='success': self.assertTrue(any(c[0]=='start' for c in calls), calls)
            if mode=='success': self.assertTrue((app/'app.jar').exists())
            else: self.assertEqual('old', (app/'old').read_text())

    def test_build_failure_preserves_running_container(self): self.scenario('build-fail')
    def test_unwritable_logs_preflight_preserves_running_container(self): self.scenario('permission-fail')
    def test_run_failure_restores_previous_container(self): self.scenario('run-fail')
    def test_health_failure_restores_previous_container(self): self.scenario('health-fail')
    def test_success_replaces_files_after_health_gate(self): self.scenario('success')

if __name__=='__main__': unittest.main()

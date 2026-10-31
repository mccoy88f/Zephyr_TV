import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
try: import nello_sync as sync
except ImportError: sync=None
class NelloSyncTest(unittest.TestCase):
 def pick(self,releases,state,published=False):
  self.assertIsNotNone(sync,'release planner must exist')
  return sync.plan(releases,state,published)
 def release(self,tag,when,pre=False):
  return dict(tag_name=tag,published_at=when,prerelease=pre,draft=False,assets=[{'name':'ZephyrTV.apk'}])
 def state(self):return dict(upstreamTag='v3.5.3',build=6,versionCode=65,customTag='v3.5.3-nello.6')
 def test_newest_beta_is_not_ignored(self):
  p=self.pick([self.release('v3.5.6','2026-10-30T12:00:00Z'),self.release('v3.6.0-beta.3','2026-10-31T01:00:00Z',True)],self.state(),True)
  self.assertEqual(p['upstreamTag'],'v3.6.0-beta.3');self.assertEqual(p['build'],7);self.assertTrue(p['prerelease'])
 def test_completed_release_is_idempotent(self):
  s=self.state();self.assertFalse(self.pick([self.release(s['upstreamTag'],'2026-10-30T12:00:00Z')],s,True)['needed'])
 def test_partial_publication_recovers_without_a_second_version(self):
  s=self.state();p=self.pick([self.release(s['upstreamTag'],'2026-10-30T12:00:00Z')],s,False)
  self.assertTrue(p['needed']);self.assertTrue(p['recover']);self.assertEqual(p['customTag'],s['customTag'])
 def test_drafts_and_non_apk_tags_never_replace_our_build(self):
  r=self.release('v9.0.0','2026-10-31T12:00:00Z');r['draft']=True
  x=self.release('v8.0.0','2026-10-31T12:00:00Z');x['assets']=[]
  self.assertFalse(self.pick([r,x],self.state(),True)['needed'])
 def test_release_tags_cannot_inject_shell_commands(self):
  r=self.release('v3.6.0; echo secret','2026-10-31T12:00:00Z')
  self.assertFalse(self.pick([r],self.state(),True)['needed'])
if __name__=='__main__':unittest.main()

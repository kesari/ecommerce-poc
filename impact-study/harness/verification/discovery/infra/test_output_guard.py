"""Offline output-path checks; no Docker, Maven or provider invocations."""
import tempfile
import unittest
from pathlib import Path
from verify import validate_outputs

class OutputGuardTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='discovery-path-guard-',dir='/private/tmp')
  self.base=Path(self.tmp.name);self.estate=self.base/'estate';self.study=self.base/'study'
  self.estate.mkdir();self.study.mkdir()
 def tearDown(self):self.tmp.cleanup()
 def check(self,runtime,evidence):return validate_outputs(runtime,evidence,self.estate,self.study)
 def test_accepts_disjoint_fresh_tmp_directories(self):
  paths=(self.base/'runtime',self.base/'evidence');self.assertEqual(paths,self.check(*paths));self.assertFalse(any(p.exists() for p in paths))
 def test_rejects_study_and_estate_evidence(self):
  for root in (self.study,self.estate):
   with self.subTest(root=root),self.assertRaisesRegex(ValueError,'outside the estate and study'):self.check(self.base/'runtime',root/'evidence')
 def test_rejects_resolved_symlink_into_estate(self):
  link=self.base/'link';link.symlink_to(self.estate,target_is_directory=True)
  with self.assertRaisesRegex(ValueError,'outside the estate and study'):self.check(self.base/'runtime',link/'evidence')
 def test_rejects_existing_nested_and_non_tmp_outputs(self):
  for runtime,evidence in ((self.base,self.base/'evidence'),(self.base/'runtime',self.base/'runtime/evidence'),(self.base/'runtime',Path('/var/imaginary-discovery-evidence'))):
   with self.subTest(runtime=runtime,evidence=evidence),self.assertRaises(ValueError):self.check(runtime,evidence)
if __name__=='__main__':unittest.main()

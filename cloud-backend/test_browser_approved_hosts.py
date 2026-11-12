import unittest

from browser_scraper import _host_allowed


class ApprovedHostBoundaryTests(unittest.TestCase):
    def test_exact_host_is_allowed(self):
        self.assertTrue(_host_allowed("example.org", ["example.org"]))
        self.assertTrue(_host_allowed("EXAMPLE.ORG.", ["example.org"]))

    def test_subdomain_suffix_and_similar_hosts_are_not_allowed(self):
        self.assertFalse(_host_allowed("sub.example.org", ["example.org"]))
        self.assertFalse(_host_allowed("evil-example.org", ["example.org"]))
        self.assertFalse(_host_allowed("example.org.evil.test", ["example.org"]))


if __name__ == "__main__":
    unittest.main()

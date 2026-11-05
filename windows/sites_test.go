package main

import (
	"reflect"
	"strings"
	"testing"
)

func TestNormalizeSite(t *testing.T) {
	cases := map[string]string{
		"https://www.YouTube.com/watch?v=1": "youtube.com",
		"  m.vk.com  ":                      "m.vk.com",
		"*.example.org":                     "example.org",
		"http://user@site.io:8443/a":        "site.io",
		"пример.рф":                         "xn--e1afmkfd.xn--p1ai",
		"8.8.8.8":                           "8.8.8.8",
		"10.0.0.0/8":                        "10.0.0.0/8",
		"https://1.2.3.4/path":              "1.2.3.4",
		"300.1.1.1":                         "",
		"hello":                             "",
		"bad_name.com":                      "",
		"":                                  "",
	}
	for in, want := range cases {
		if got := NormalizeSite(in); got != want {
			t.Errorf("%q: got %q, want %q", in, got, want)
		}
	}
	if got := SiteLabel("xn--e1afmkfd.xn--p1ai"); got != "пример.рф" {
		t.Errorf("label %q", got)
	}
	if got := ParseSites("a.com\nb.com, a.com junk"); !reflect.DeepEqual(got, []string{"a.com", "b.com"}) {
		t.Errorf("parse %v", got)
	}
}

func TestDirectSitesInConfig(t *testing.T) {
	servers := []*Server{{Tag: "s1", Name: "s1", Outbound: map[string]any{"type": "vless", "tag": "s1"}}}
	cfg, err := BuildSingBox(servers, BuildOptions{RuDirect: true, BlockedVPN: true, DirectSites: []string{"youtube.com", "1.1.1.1"}})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"domain_suffix":["youtube.com"],"outbound":"direct"`, `"ip_cidr":["1.1.1.1/32"],"outbound":"direct"`,
		`"domain_suffix":["youtube.com"],"server":"dns-direct"`} {
		if !strings.Contains(strings.ReplaceAll(cfg, " ", ""), want) {
			t.Errorf("config lacks %s", want)
		}
	}
	// the user's list must beat "blocked in Russia → VPN"
	c := strings.ReplaceAll(cfg, " ", "")
	if own, bl := strings.Index(c, `"domain_suffix":["youtube.com"],"outbound"`), strings.Index(c, `"rule_set":["ru-blocked","ru-blocked-ip"]`); bl < 0 || own > bl {
		t.Errorf("own sites come after the blocked list")
	}
}

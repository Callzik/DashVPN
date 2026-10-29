package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSubInfo(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	const gib = int64(1 << 30)
	at := func(days float64) int64 { return now.Unix() + int64(days*86400) }

	a := ParseSubInfo(fmt.Sprintf("upload=%d; download=%d; total=%d; expire=%d", 2*gib, 10*gib, 50*gib, at(38)-100))
	if a == nil || a.Used != 12*gib || a.DaysLeft(now) != 38 || a.Text(now) != "12 / 50 ГБ · 38 дн" || a.Low(now) || a.Over(now) {
		t.Fatalf("regular: %+v %q", a, a.Text(now))
	}
	unl := ParseSubInfo(fmt.Sprintf("upload=0; download=123; total=0; expire=%d", at(120)))
	if unl.Text(now) != "∞ · 120 дн" || unl.UsedPart() != 0 {
		t.Fatalf("unlimited: %q", unl.Text(now))
	}
	low := ParseSubInfo(fmt.Sprintf("upload=0;download=%d;total=%d;expire=0", 93*gib, 100*gib))
	if !low.Low(now) || low.DaysLeft(now) != -1 || low.Text(now) != "93 / 100 ГБ" {
		t.Fatalf("low traffic: %q", low.Text(now))
	}
	if !ParseSubInfo(fmt.Sprintf("total=0; expire=%d", at(2))).Low(now) {
		t.Fatal("2 days should be low")
	}
	old := ParseSubInfo(fmt.Sprintf("upload=1; download=1; total=0; expire=%d", now.Unix()-10))
	if !old.Over(now) || old.Text(now) != "закончилась" {
		t.Fatal("expired")
	}
	if !ParseSubInfo(fmt.Sprintf("download=%d; total=%d", 5*gib, 5*gib)).Over(now) {
		t.Fatal("traffic spent")
	}
	for _, h := range []string{"", "garbage", "upload=1; download=2; total=0; expire=0"} {
		if ParseSubInfo(h) != nil {
			t.Fatalf("%q should give nothing", h)
		}
	}
	if ParseSubInfo("total=5.36870912E10; expire=abc") == nil {
		t.Fatal("float total")
	}
	if got := ParseSubInfo(fmt.Sprintf("download=%d; total=%d", 3*gib/2, 2*gib)).Text(now); got != "1.5 / 2 ГБ" {
		t.Fatalf("fraction: %q", got)
	}
	if MostUrgent([]*SubInfo{a, nil, low, unl}, now) != low || MostUrgent([]*SubInfo{a, old, low}, now) != old ||
		MostUrgent([]*SubInfo{unl, a}, now) != a || MostUrgent([]*SubInfo{nil}, now) != nil {
		t.Fatal("most urgent")
	}
}

func TestLoadSubsKeepsInfo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("subscription-userinfo", "upload=1; download=2; total=1073741824; expire=0")
		fmt.Fprint(w, "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=tls&type=tcp#NL\n")
	}))
	defer srv.Close()
	dir := t.TempDir()
	p := LoadPrefs(dir)
	p.AddSub(srv.URL)
	c := NewCore(p, dir, dir, dir, &Log{})
	if got := c.loadSubs([]string{srv.URL}); len(got) != 1 {
		t.Fatalf("subs: %v", got)
	}
	if ring := c.SubRing(time.Now()); ring == nil || ring["label"] != "∞" {
		t.Fatalf("ring: %v", ring)
	}
	p.RemoveSub(srv.URL)
	if c.SubRing(time.Now()) != nil {
		t.Fatal("ring after remove")
	}
}

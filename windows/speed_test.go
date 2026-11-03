package main

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestSpeedPicks(t *testing.T) {
	members := []string{"srv1", "srv2", "srv3", "srv4", "srv5"}
	delays := map[string]int{"srv1": 300, "srv2": 90, "srv3": 120, "srv5": 60}
	if got := speedPicks(members, delays, "srv2", nil); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3", "srv1"}) {
		t.Fatalf("all answering by ping: %v", got)
	}
	if got := speedPicks(members, delays, "srv4", nil); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3", "srv1", "srv4"}) {
		t.Fatalf("current added: %v", got)
	}
	if got := speedPicks(members, delays, "srv2", map[string]bool{"srv5": true}); !reflect.DeepEqual(got, []string{"srv2", "srv3", "srv1"}) {
		t.Fatalf("stalled server left out: %v", got)
	}
	if got := spares(members, delays, map[string]bool{"srv5": true, "srv2": true}); !reflect.DeepEqual(got, []string{"srv3", "srv1"}) {
		t.Fatalf("spares skip the stalled and the current: %v", got)
	}
	if got := spares(members, delays, map[string]bool{"srv1": true, "srv2": true, "srv3": true, "srv5": true}); len(got) != 0 {
		t.Fatalf("no spares: %v", got)
	}
}

func TestManyCandidates(t *testing.T) {
	var all []string
	delays := map[string]int{}
	for i := 1; i <= 30; i++ {
		s := "s" + strconv.Itoa(i)
		all = append(all, s)
		delays[s] = 1000 - i
	}
	p := speedPicks(all, delays, "s1", nil)
	if len(p) != speedCandidates+1 || p[0] != "s30" || p[len(p)-1] != "s1" {
		t.Fatalf("20 best by ping plus current: %v", p)
	}
	if len(spares(all, delays, nil)) != spareCount {
		t.Fatalf("load check spares stay few")
	}
	if got := finalists(map[string]float64{"a": 5, "b": 40, "c": 20, "d": 30, "e": 0}, "a"); !reflect.DeepEqual(got, []string{"b", "d", "c", "a"}) {
		t.Fatalf("three fastest plus current: %v", got)
	}
	if got := finalists(map[string]float64{"a": 50, "b": 40, "c": 20, "d": 30}, "a"); !reflect.DeepEqual(got, []string{"a", "b", "d"}) {
		t.Fatalf("current among the fastest: %v", got)
	}
	if got := finalists(map[string]float64{"a": 0, "b": 0}, "a"); !reflect.DeepEqual(got, []string{"a"}) {
		t.Fatalf("nothing downloaded: %v", got)
	}
}

func TestFasterPick(t *testing.T) {
	cases := []struct {
		speeds map[string]float64
		cur    string
		want   string
	}{
		{map[string]float64{"a": 3, "b": 40, "c": 20}, "a", "b"}, // overloaded current
		{map[string]float64{"a": 30, "b": 40}, "a", ""},          // not 1.5 times faster
		{map[string]float64{"a": 1, "b": 2.5}, "a", ""},          // 2.5x but only 1.5 Mbit/s more
		{map[string]float64{"a": 0, "b": 5}, "a", "b"},           // current did not download
		{map[string]float64{"a": 50, "b": 10}, "a", ""},          // current is the fastest
		{map[string]float64{"a": 0, "b": 0}, "a", ""},            // nothing downloaded
	}
	for i, c := range cases {
		if got := fasterPick(c.speeds, c.cur); got != c.want {
			t.Errorf("case %d: got %q, want %q", i, got, c.want)
		}
	}
}

func TestLoadOK(t *testing.T) {
	// a plain HTTP URL makes the speed-in "proxy" receive the request itself, so a test server can play it
	size := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(131072))
		_, _ = w.Write(make([]byte, size)) // fewer bytes than promised: the connection is cut like a stalled one
	}))
	defer srv.Close()
	port, _ := strconv.Atoi(srv.URL[strings.LastIndex(srv.URL, ":")+1:])
	old := LoadURL
	LoadURL = "http://load.test/file"
	defer func() { LoadURL = old }()
	size = 131072
	if !LoadOK(port, "srv1", "x") {
		t.Fatal("full file must pass")
	}
	size = 20 * 1024
	if LoadOK(port, "srv1", "x") {
		t.Fatal("file cut at 20 KB must fail")
	}
}

func TestQuickRound(t *testing.T) {
	// every server gets a download, never more than speedParallel at once
	var mu sync.Mutex
	open, peak := 0, 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		open++
		if open > peak {
			peak = open
		}
		mu.Unlock()
		w.Header().Set("Content-Length", strconv.Itoa(300*1024))
		for i := 0; i < 10; i++ {
			time.Sleep(10 * time.Millisecond)
			if i == 9 {
				mu.Lock()
				open-- // the client may finish as soon as the last bytes are out
				mu.Unlock()
			}
			_, _ = w.Write(make([]byte, 30*1024))
			w.(http.Flusher).Flush()
		}
	}))
	defer srv.Close()
	port, _ := strconv.Atoi(srv.URL[strings.LastIndex(srv.URL, ":")+1:])
	old := QuickURL
	QuickURL = "http://quick.test/file"
	defer func() { QuickURL = old }()
	var tags []string
	for i := 0; i < 12; i++ {
		tags = append(tags, "s"+strconv.Itoa(i))
	}
	q := quickRound(tags, port, "x", func() bool { return true })
	if len(q) != len(tags) {
		t.Fatalf("measured %d of %d", len(q), len(tags))
	}
	for tag, v := range q {
		if v <= 0 {
			t.Fatalf("%s: no speed", tag)
		}
	}
	if peak > speedParallel {
		t.Fatalf("%d downloads at once", peak)
	}
	if q := quickRound(tags, port, "x", func() bool { return false }); len(q) != 0 {
		t.Fatalf("VPN off: nothing measured, got %d", len(q))
	}
}

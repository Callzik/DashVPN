package main

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

func TestSpeedPicks(t *testing.T) {
	members := []string{"srv1", "srv2", "srv3", "srv4", "srv5"}
	delays := map[string]int{"srv1": 300, "srv2": 90, "srv3": 120, "srv5": 60}
	if got := speedPicks(members, delays, "srv2", nil); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3"}) {
		t.Fatalf("current among the best: %v", got)
	}
	if got := speedPicks(members, delays, "srv1", nil); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3", "srv1"}) {
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

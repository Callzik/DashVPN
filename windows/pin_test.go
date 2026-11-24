package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestWatchKeepsManualServerWhenProbesFail(t *testing.T) {
	const name, tag = "Manual location", "s1"
	var available atomic.Bool
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/proxies/"+Selector:
			fmt.Fprintf(w, `{"now":%q}`, tag)
		case r.Method == http.MethodGet && r.URL.Path == "/proxies/"+tag+"/delay":
			if available.Load() {
				fmt.Fprint(w, `{"delay":42}`)
			} else {
				http.Error(w, "timed out", http.StatusGatewayTimeout)
			}
		default:
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer api.Close()

	prefs := LoadPrefs(t.TempDir())
	prefs.Update(func(d *PrefsData) { d.Pinned = name })
	c := NewCore(prefs, "", "", "", &Log{})
	c.clash = &Clash{base: api.URL, hc: api.Client()}
	c.state = StOn
	c.byTag[tag] = &Server{Name: name, Tag: tag}

	for range 3 {
		c.watch(c.gen)
	}
	if got := prefs.Get().Pinned; got != name {
		t.Errorf("saved manual server = %q, want %q", got, name)
	}
	if got := c.View().Ping; got != -1 {
		t.Errorf("ping after failures = %d, want -1", got)
	}
	if got := strings.Count(c.log.Text(), "Выбранный сервер не отвечает"); got != 1 {
		t.Errorf("warnings after three failures = %d, want 1", got)
	}

	available.Store(true)
	c.watch(c.gen)
	if got := c.View().Ping; got != 42 {
		t.Errorf("ping after recovery = %d, want 42", got)
	}
}

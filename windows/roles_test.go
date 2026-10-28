package main

import (
	"encoding/json"
	"testing"
)

func TestRolesInGroups(t *testing.T) {
	mk := func(name string, g int) *Server {
		return &Server{Name: name, Tag: name, Group: g, Outbound: map[string]any{"type": "direct", "tag": name}}
	}
	list := []*Server{mk("a", GroupRegular), mk("b", GroupLTE), mk("c", GroupRegular), mk("d", GroupExcluded)}
	ApplyOff(ApplyRoles(list, map[string]string{"a": "lte", "b": "both", "d": "regular"}), map[string]bool{"c": true})
	raw, err := BuildSingBox(list, BuildOptions{})
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		Outbounds []struct {
			Tag       string   `json:"tag"`
			Outbounds []string `json:"outbounds"`
		} `json:"outbounds"`
	}
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatal(err)
	}
	got := map[string][]string{}
	for _, o := range cfg.Outbounds {
		got[o.Tag] = o.Outbounds
	}
	eq := func(a, b []string) bool {
		if len(a) != len(b) {
			return false
		}
		for i := range a {
			if a[i] != b[i] {
				return false
			}
		}
		return true
	}
	if r := got[GroupNameRegular]; !eq(r, []string{"b", "d"}) {
		t.Errorf("regular group: %v, want [b d]", r)
	}
	if l := got[GroupNameLTE]; !eq(l, []string{"a", "b"}) {
		t.Errorf("LTE group: %v, want [a b]", l)
	}
}

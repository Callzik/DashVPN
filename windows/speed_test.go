package main

import (
	"reflect"
	"testing"
)

func TestSpeedPicks(t *testing.T) {
	members := []string{"srv1", "srv2", "srv3", "srv4", "srv5"}
	delays := map[string]int{"srv1": 300, "srv2": 90, "srv3": 120, "srv5": 60}
	if got := speedPicks(members, delays, "srv2"); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3"}) {
		t.Fatalf("current among the best: %v", got)
	}
	if got := speedPicks(members, delays, "srv1"); !reflect.DeepEqual(got, []string{"srv5", "srv2", "srv3", "srv1"}) {
		t.Fatalf("current added: %v", got)
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

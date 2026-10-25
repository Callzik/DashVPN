package main

import "testing"

func TestWhatsNew(t *testing.T) {
	if got := whatsNew(Version); len(got) != 0 {
		t.Fatalf("nothing is new for the running version, got %v", got)
	}
	if got, want := len(whatsNew("")), len(changelog[Version]); got != want {
		t.Fatalf("settings without a seen version get only what came after 1.5.3: %d lines, want %d", got, want)
	}
	all := whatsNew("1.4.9")
	if all[0] != changelog["1.5.0"][0] || all[len(all)-1] != changelog[Version][len(changelog[Version])-1] {
		t.Fatalf("entries must go oldest first: %v", all)
	}
}

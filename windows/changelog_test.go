package main

import "testing"

func TestWhatsNew(t *testing.T) {
	if got := whatsNew(Version); len(got) != 0 {
		t.Fatalf("nothing is new for the running version, got %v", got)
	}
	want := 0
	for v, lines := range changelog {
		if newer(v, "1.5.3") {
			want += len(lines)
		}
	}
	if got := len(whatsNew("")); got != want {
		t.Fatalf("settings without a seen version get only what came after 1.5.3: %d lines, want %d", got, want)
	}
	all := whatsNew("1.4.9")
	if all[0] != changelog["1.5.0"][0] || all[len(all)-1] != changelog[Version][len(changelog[Version])-1] {
		t.Fatalf("entries must go oldest first: %v", all)
	}
}

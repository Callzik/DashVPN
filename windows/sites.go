package main

import (
	"regexp"
	"strconv"
	"strings"
)

// Sites the user sends around the VPN: whatever is typed or pasted becomes a bare domain or an IPv4 (with /mask).
// Same rules as Sites.java on Android.

var (
	siteIP4   = regexp.MustCompile(`^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(/\d{1,2})?$`)
	siteLabel = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|xn--[a-z0-9-]+)$`)
)

// NormalizeSite turns "https://www.YouTube.com/watch?v=1" into "youtube.com"; "" when it is not a site.
func NormalizeSite(in string) string {
	s := strings.ToLower(strings.TrimSpace(in))
	if i := strings.Index(s, "://"); i >= 0 {
		s = s[i+3:]
	}
	s = cutAny(s, "?#")
	if !siteIP4.MatchString(s) {
		s = cutAny(s, "/") // keep a /mask of an IP, drop a page path
	}
	if i := strings.LastIndex(s, "@"); i >= 0 {
		s = s[i+1:]
	}
	if i := strings.Index(s, ":"); i >= 0 {
		s = s[:i]
	}
	for strings.HasPrefix(s, "*.") || strings.HasPrefix(s, ".") {
		s = strings.TrimPrefix(strings.TrimPrefix(s, "*"), ".")
	}
	s = strings.TrimPrefix(s, "www.")
	s = strings.TrimRight(s, ".")
	if s == "" {
		return ""
	}
	if m := siteIP4.FindStringSubmatch(s); m != nil {
		for _, g := range m[1:5] {
			if n, _ := strconv.Atoi(g); n > 255 {
				return ""
			}
		}
		if m[5] != "" {
			if n, _ := strconv.Atoi(m[5][1:]); n > 32 {
				return ""
			}
		}
		return s
	}
	labels := strings.Split(s, ".")
	if len(labels) < 2 {
		return ""
	}
	for i, l := range labels {
		if a, ok := punyLabel(l); ok {
			labels[i] = a
		} else {
			return ""
		}
		if !siteLabel.MatchString(labels[i]) {
			return ""
		}
	}
	if _, err := strconv.Atoi(labels[len(labels)-1]); err == nil {
		return ""
	}
	s = strings.Join(labels, ".")
	if len(s) > 253 {
		return ""
	}
	return s
}

func cutAny(s, chars string) string {
	if i := strings.IndexAny(s, chars); i >= 0 {
		return s[:i]
	}
	return s
}

// ParseSites splits a stored or pasted list (lines, spaces, commas), dropping junk and repeats.
func ParseSites(text string) []string {
	var out []string
	seen := map[string]bool{}
	for _, part := range strings.FieldsFunc(text, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\r' || r == '\t'
	}) {
		if n := NormalizeSite(part); n != "" && !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

// siteRules splits the list into sing-box domain_suffix and ip_cidr values.
func siteRules(sites []string) (domains, cidrs []any) {
	for _, s := range sites {
		if siteIP4.MatchString(s) {
			if !strings.Contains(s, "/") {
				s += "/32"
			}
			cidrs = append(cidrs, s)
		} else {
			domains = append(domains, s)
		}
	}
	return
}

// SiteLabel shows a punycode domain in its own letters (пример.рф).
func SiteLabel(s string) string {
	labels := strings.Split(s, ".")
	for i, l := range labels {
		if strings.HasPrefix(l, "xn--") {
			if u, ok := punyDecode(l[4:]); ok {
				labels[i] = u
			}
		}
	}
	return strings.Join(labels, ".")
}

// Punycode (RFC 3492) for one label.
const (
	pBase, pTmin, pTmax, pSkew, pDamp, pBias, pN = 36, 1, 26, 38, 700, 72, 128
)

func pAdapt(delta, num int, first bool) int {
	if first {
		delta /= pDamp
	} else {
		delta /= 2
	}
	delta += delta / num
	k := 0
	for delta > ((pBase-pTmin)*pTmax)/2 {
		delta /= pBase - pTmin
		k += pBase
	}
	return k + (pBase-pTmin+1)*delta/(delta+pSkew)
}

func pDigit(d int) byte {
	if d < 26 {
		return byte('a' + d)
	}
	return byte('0' + d - 26)
}

func punyLabel(l string) (string, bool) {
	rs := []rune(l)
	var b strings.Builder
	basic := 0
	for _, r := range rs {
		if r < 0x80 {
			b.WriteRune(r)
			basic++
		}
	}
	if basic == len(rs) {
		return l, true
	}
	if basic > 0 {
		b.WriteByte('-')
	}
	n, delta, bias, h := pN, 0, pBias, basic
	for h < len(rs) {
		m := int(^uint(0) >> 1)
		for _, r := range rs {
			if int(r) >= n && int(r) < m {
				m = int(r)
			}
		}
		delta += (m - n) * (h + 1)
		n = m
		for _, r := range rs {
			if int(r) < n {
				delta++
			}
			if int(r) == n {
				q := delta
				for k := pBase; ; k += pBase {
					t := k - bias
					if t < pTmin {
						t = pTmin
					} else if t > pTmax {
						t = pTmax
					}
					if q < t {
						break
					}
					b.WriteByte(pDigit(t + (q-t)%(pBase-t)))
					q = (q - t) / (pBase - t)
				}
				b.WriteByte(pDigit(q))
				bias = pAdapt(delta, h+1, h == basic)
				delta = 0
				h++
			}
		}
		delta++
		n++
	}
	return "xn--" + b.String(), true
}

func punyDecode(s string) (string, bool) {
	var out []rune
	if i := strings.LastIndex(s, "-"); i >= 0 {
		out = []rune(s[:i])
		s = s[i+1:]
	}
	n, i, bias := pN, 0, pBias
	for p := 0; p < len(s); {
		old, w := i, 1
		for k := pBase; ; k += pBase {
			if p >= len(s) {
				return "", false
			}
			c := s[p]
			p++
			var d int
			switch {
			case c >= 'a' && c <= 'z':
				d = int(c - 'a')
			case c >= '0' && c <= '9':
				d = int(c-'0') + 26
			default:
				return "", false
			}
			i += d * w
			t := k - bias
			if t < pTmin {
				t = pTmin
			} else if t > pTmax {
				t = pTmax
			}
			if d < t {
				break
			}
			w *= pBase - t
		}
		bias = pAdapt(i-old, len(out)+1, old == 0)
		n += i / (len(out) + 1)
		i %= len(out) + 1
		out = append(out[:i], append([]rune{rune(n)}, out[i:]...)...)
		i++
	}
	return string(out), true
}

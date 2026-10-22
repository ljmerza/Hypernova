import { describe, expect, it } from "vitest";
import { isPrivateAddress, isPublicAddress, parseIp } from "../src/ip.ts";
import { randomName } from "../src/names.ts";

describe("parseIp", () => {
  it("parses IPv4 strictly", () => {
    expect(parseIp("192.168.1.1")?.text).toBe("192.168.1.1");
    expect(parseIp(" 10.0.0.1 ")?.text).toBe("10.0.0.1");
    for (const bad of ["192.168.1", "192.168.1.1.1", "192.168.01.1", "256.1.1.1", "1.2.3.-4", "", "a.b.c.d"]) {
      expect(parseIp(bad), bad).toBeNull();
    }
  });

  it("parses IPv6 into one canonical spelling", () => {
    expect(parseIp("fd00::1")?.text).toBe("fd00:0:0:0:0:0:0:1");
    expect(parseIp("FD00:0000:0000:0000:0000:0000:0000:0001")?.text).toBe("fd00:0:0:0:0:0:0:1");
    expect(parseIp("::ffff:192.168.0.1")?.text).toBe("0:0:0:0:0:ffff:c0a8:1");
    expect(parseIp("::")?.text).toBe("0:0:0:0:0:0:0:0");
    for (const bad of ["fd00:::1", "fd00::1::2", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "fd00::g", "fe80::1%en0", "12345::1"]) {
      expect(parseIp(bad), bad).toBeNull();
    }
  });
});

describe("address classes", () => {
  const priv = ["10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.255", "fd00::1", "fc00::1"];
  const neither = ["127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "::1", "fe80::1", "ff02::1", "2001:db8::1", "::ffff:8.8.8.8"];
  const pub = ["8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888", "2a00:1450:4001::1"];

  it("knows private addresses", () => {
    for (const a of priv) expect(isPrivateAddress(parseIp(a)!), a).toBe(true);
    for (const a of [...neither, ...pub]) expect(isPrivateAddress(parseIp(a)!), a).toBe(false);
  });

  it("knows public addresses", () => {
    for (const a of pub) expect(isPublicAddress(parseIp(a)!), a).toBe(true);
    for (const a of [...neither, ...priv]) expect(isPublicAddress(parseIp(a)!), a).toBe(false);
  });
});

describe("randomName", () => {
  it("is 10 lowercase letters or digits, and different every time", () => {
    const seen = new Set<string>();
    for (let i = 0; i < 200; i++) {
      const n = randomName();
      expect(n).toMatch(/^[a-z0-9]{10}$/);
      seen.add(n);
    }
    expect(seen.size).toBe(200);
  });
});

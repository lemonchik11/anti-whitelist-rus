package dev.yakovsava.antiwhitelist.service

/** Splits a CIDR block excluding one /32 host (anti-loop for TURN IP). */
object CidrExclusion {
    fun exclude(network: String, host: String): List<String> {
        if (host.isEmpty()) return listOf(network)
        val (ni, p) = parseNet(network); val hi = ip2i(host)
        return buildList {
            var c = ni; val end = if (p == 0) -1 else ni or ((1 shl (32-p))-1)
            while (ul(c) < ul(hi))    { val s = lb(c, hi-1, end); add("${i2ip(c)}/$s"); c += (1 shl (32-s)) }
            c = hi + 1
            while (ul(c) <= ul(end)) { val s = lb(c, end, end); add("${i2ip(c)}/$s"); val nx = c + (1 shl (32-s)); if (nx==c) break; c=nx }
        }
    }
    private fun lb(a: Int, lim: Int, re: Int): Int {
        var s=0
        while (s<31) { if (a and (1 shl (32-(s+1)))!=0) break; val be=a+(1 shl (32-(s+1)))-1; if (ul(be)>ul(lim)||ul(be)>ul(re)) break; s++ }
        return s+1
    }
    private fun parseNet(c: String): Pair<Int,Int> { val(i,ps)=c.split("/"); val p=ps.toInt(); val m=if(p==0) 0 else(-1 shl(32-p)); return(ip2i(i) and m) to p }
    private fun ip2i(ip: String): Int { val p=ip.split(".").map{it.toInt()}; return(p[0] shl 24)or(p[1] shl 16)or(p[2] shl 8)or p[3] }
    private fun i2ip(v: Int): String { val l=v.toLong() and 0xFFFFFFFFL; return "${(l shr 24)and 0xFF}.${(l shr 16)and 0xFF}.${(l shr 8)and 0xFF}.${l and 0xFF}" }
    private fun ul(v: Int) = v.toLong() and 0xFFFFFFFFL
}

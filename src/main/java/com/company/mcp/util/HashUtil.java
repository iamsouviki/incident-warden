package com.company.mcp.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * ponytail: run once to generate seed hashes, not shipped in production
 *   mvn -q exec:java -Dexec.mainClass=com.company.mcp.util.HashUtil
 */
public class HashUtil {
    public static void main(String[] args) {
        var enc = new BCryptPasswordEncoder(10); // cost 10 is fast enough for seeds
        System.out.println("admin123:   " + enc.encode("admin123"));
        System.out.println("analyst123: " + enc.encode("analyst123"));
        System.out.println("viewer123:  " + enc.encode("viewer123"));
    }
}

package com.company.mcp.util;

import com.company.mcp.controller.AuthController;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * ponytail: run once to regenerate the seed hash in changelog 1.20, not shipped in production
 *   mvn -q exec:java -Dexec.mainClass=com.company.mcp.util.HashUtil
 *
 * Prints the one default the product has. It used to print three (admin123/analyst123/
 * viewer123) for accounts that no longer exist.
 */
public class HashUtil {
    public static void main(String[] args) {
        var enc = new BCryptPasswordEncoder(10); // cost 10 is fast enough for seeds
        System.out.println(AuthController.DEFAULT_PASSWORD + ": " + enc.encode(AuthController.DEFAULT_PASSWORD));
    }
}

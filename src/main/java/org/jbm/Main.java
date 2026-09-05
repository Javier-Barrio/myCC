package org.jbm;

import org.jbm.cc.CppTokenizer;
import org.jbm.cc.cpp.Scanner;

public class Main {
    public static void main(String[] args) {


        new Scanner().expand(CppTokenizer.tokenSet("""
            #define A 12
            #define B A13
            int a = A;
            int b = B;
        """));
    }
}
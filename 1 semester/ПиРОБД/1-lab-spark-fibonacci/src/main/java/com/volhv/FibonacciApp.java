package com.volhv;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import java.util.ArrayList;
import java.util.List;

public class FibonacciApp {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: FibonacciApp <N>");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);

        SparkConf conf = new SparkConf().setAppName("FibonacciApp");

        try(JavaSparkContext sc = new JavaSparkContext(conf)) {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i <= n; i++) {
                indices.add(i);
            }

            JavaRDD<Integer> rdd = sc.parallelize(indices);

            long count = rdd.count();

            long result = fibonacci(n);

            System.out.println("=======================================");
            System.out.println("Elements processed by Spark job: " + count);
            System.out.println("Fibonacci(" + n + ") = " + result);
            System.out.println("=======================================");
        }
    }

    private static long fibonacci(int n) {
        if (n <= 1) return n;
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            b = a + b;
            a = b - a;
        }
        return b;
    }
}

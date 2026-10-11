package ru.fkit.bi.generator.run;

/** Another process owns the durable run lease; this worker must stop mutating state. */
final class LeaseLostException extends RuntimeException {}

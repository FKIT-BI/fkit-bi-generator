package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.SplittableRandom;
import java.util.UUID;

/** Deterministic per-signal behavior evaluation; does not depend on worker ordering. */
final class RealtimeBehaviorEvaluator {
  Result evaluate(JsonNode behavior, String valueType, long runSeed, UUID machineId, UUID signalId,
      String phaseCode, long cycle, long tickIndex, long elapsedMs) {
    long seed = behavior.hasNonNull("seedOverride") ? behavior.path("seedOverride").asLong()
        : mix(runSeed ^ machineId.getMostSignificantBits() ^ machineId.getLeastSignificantBits()
            ^ signalId.getMostSignificantBits() ^ signalId.getLeastSignificantBits()
            ^ phaseCode.hashCode() ^ cycle * 0x9e3779b97f4a7c15L ^ tickIndex);
    SplittableRandom random = new SplittableRandom(seed);
    if (random.nextDouble() < behavior.path("missingProbability").asDouble(0d)) return new Result(null, quality(behavior), true);
    String model = behavior.path("model").asText();
    Object value;
    if (model.equals("CONSTANT")) value = constant(behavior.path("value"), valueType);
    else if (model.equals("UNIFORM")) {
      double generated = behavior.path("min").asDouble() + random.nextDouble() * (behavior.path("max").asDouble() - behavior.path("min").asDouble());
      value = numeric(generated, valueType);
    } else if (model.equals("NORMAL")) {
      double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
      double gaussian = Math.sqrt(-2d * Math.log(u1)) * Math.cos(2d * Math.PI * random.nextDouble());
      double generated = behavior.path("mean").asDouble() + gaussian * behavior.path("standardDeviation").asDouble();
      if (behavior.has("clampMin")) generated = Math.max(generated, behavior.path("clampMin").asDouble());
      if (behavior.has("clampMax")) generated = Math.min(generated, behavior.path("clampMax").asDouble());
      value = numeric(generated, valueType);
    } else if (model.equals("RAMP")) {
      long duration = behavior.path("durationMs").asLong();
      long position = behavior.path("repeat").asBoolean() ? Math.floorMod(elapsedMs, duration) : Math.min(elapsedMs, duration);
      double ratio = Math.min(1d, (double) position / duration);
      value = numeric(behavior.path("from").asDouble() + ratio * (behavior.path("to").asDouble() - behavior.path("from").asDouble()), valueType);
    } else if (model.equals("SINE")) {
      double radians = 2d * Math.PI * elapsedMs / behavior.path("periodMs").asLong() + behavior.path("phaseRadians").asDouble(0d);
      value = numeric(behavior.path("center").asDouble() + behavior.path("amplitude").asDouble() * Math.sin(radians), valueType);
    } else if (model.equals("COUNTER_RATE")) {
      double generated = behavior.path("initialValue").asDouble() + behavior.path("incrementPerSecond").asDouble() * elapsedMs / 1000d;
      if (behavior.has("resetThreshold") && generated >= behavior.path("resetThreshold").asDouble())
        generated = behavior.path("resetValue").asDouble(0d) + Math.floorMod((long) generated, (long) behavior.path("resetThreshold").asDouble());
      value = numeric(generated, valueType);
    } else if (model.equals("PULSE")) {
      long on = behavior.path("onDurationMs").asLong(), off = behavior.path("offDurationMs").asLong();
      long phase = Math.floorMod(elapsedMs, on + off);
      boolean initial = behavior.path("initialValue").asBoolean();
      value = phase < (initial ? on : off) ? initial : !initial;
    } else if (model.equals("BERNOULLI")) value = random.nextDouble() < behavior.path("trueProbability").asDouble();
    else if (model.equals("SEQUENCE")) {
      JsonNode values = behavior.path("values");
      long dwell = behavior.path("dwellMs").asLong();
      long position = elapsedMs / dwell;
      if (behavior.path("repeat").asBoolean()) position = Math.floorMod(position, values.size());
      else position = Math.min(position, values.size() - 1L);
      value = values.get((int) position).asText();
    } else throw new IllegalArgumentException("Unsupported behavior model: " + model);

    if (value instanceof Number number && behavior.path("noiseStandardDeviation").asDouble(0d) > 0d) {
      double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
      double gaussian = Math.sqrt(-2d * Math.log(u1)) * Math.cos(2d * Math.PI * random.nextDouble());
      value = numeric(number.doubleValue() + gaussian * behavior.path("noiseStandardDeviation").asDouble(), valueType);
    }
    if (value instanceof Double number && !Double.isFinite(number)) throw new IllegalArgumentException("Behavior generated non-finite value");
    return new Result(value, quality(behavior), false);
  }

  private Object constant(JsonNode value, String type) {
    return switch (type) {
      case "DOUBLE" -> value.asDouble();
      case "LONG" -> value.asLong();
      case "BOOLEAN" -> value.asBoolean();
      case "STRING" -> value.asText();
      default -> throw new IllegalArgumentException("Unsupported signal value type: " + type);
    };
  }
  private Object numeric(double value, String type) {
    if ("LONG".equals(type)) return Long.valueOf((long) value);
    return Double.valueOf(value);
  }
  private short quality(JsonNode behavior) {
    return switch (behavior.path("sourceQuality").asText("GOOD")) { case "UNCERTAIN" -> 1; case "BAD" -> 2; default -> 0; };
  }
  private long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
  record Result(Object value, short quality, boolean missing) {}
}

package fft_battleground.tournament;

import java.io.InputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Streaming reducer for the v2 forecast history (120MB / 553k predictions):
 * readTree() materializes ~2-4GB of JsonNodes and OOMs the 3.8GB prod box —
 * the zeroed-stats bug of the grand window (parse ran 16s, then the OOM catch
 * served empty aggregates under the v2 modelVersion label, because the catch
 * path also derives modelVersion from the served model). This walks the
 * predictions array with O(1) heap.
 */
public class StreamingForecastStats {

	public static class Result {
		public int holdoutScored, holdoutHits, inSampleScored, inSampleHits;
		public String model = "unknown";
		public int modelVersion = 0;
	}

	public static Result reduce(InputStream in, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
		Result result = new Result();
		try (JsonParser parser = mapper.getFactory().createParser(in)) {
			JsonToken token = parser.nextToken();
			while(token != null) {
				if(token == JsonToken.FIELD_NAME) {
					String field = parser.currentName();
					if("predictions".equals(field)) {
						if(parser.nextToken() == JsonToken.START_ARRAY) {
							this_scan: while(parser.nextToken() == JsonToken.START_OBJECT) {
								boolean correct = false;
								boolean inSample = false;
								while(parser.nextToken() != JsonToken.END_OBJECT) {
									String rowField = parser.currentName();
									JsonToken value = parser.nextToken();
									if(value == null) {
										break this_scan;
									}
									if("correct".equals(rowField) && value.isBoolean()) {
										correct = parser.getBooleanValue();
									} else if("in_sample".equals(rowField) && value.isBoolean()) {
										inSample = parser.getBooleanValue();
									} else if(value.isStructStart()) {
										parser.skipChildren();
									}
								}
								if(inSample) {
									result.inSampleScored++;
									if(correct) {
										result.inSampleHits++;
									}
								} else {
									result.holdoutScored++;
									if(correct) {
										result.holdoutHits++;
									}
								}
							}
						}
						return result; // predictions consumed — the header precedes it
					} else if("model".equals(field)) {
						if(parser.nextToken() == JsonToken.VALUE_STRING) {
							result.model = parser.getText();
						}
					} else if("model_version".equals(field)) {
						if(parser.nextToken() == JsonToken.VALUE_NUMBER_INT) {
							result.modelVersion = parser.getIntValue();
						}
					} else {
						JsonToken value = parser.nextToken();
						if(value != null && value.isStructStart()) {
							parser.skipChildren();
						}
					}
				}
				//NO struct skip at loop level: skipChildren on the root START_OBJECT consumes
				//the whole document (the zero-count trap); struct values of uninteresting
				//FIELDS are skipped inside the FIELD_NAME branch
				token = parser.nextToken();
			}
		}
		return result;
	}
}

package ai.voicecraft.project;

import jakarta.persistence.Embeddable;

/** Parametry generowania projektu. Zmiana dowolnego z nich oznacza fragmenty jako nieaktualne. */
@Embeddable
public class VoiceSettings {
    public String engine = "voxcpm2";
    public String language = "pl";
    public String voiceId;
    public String style;
    public String emotion;
    public double speed = 1.0;
    public double expressiveness = 0.5;
    public double cfgValue = 2.0;
    public int inferenceTimesteps = 10;
    public boolean normalize = true;
    public boolean denoise = false;
    public Integer seed;

    public VoiceSettings copy() {
        var c = new VoiceSettings();
        c.engine = engine;
        c.language = language;
        c.voiceId = voiceId;
        c.style = style;
        c.emotion = emotion;
        c.speed = speed;
        c.expressiveness = expressiveness;
        c.cfgValue = cfgValue;
        c.inferenceTimesteps = inferenceTimesteps;
        c.normalize = normalize;
        c.denoise = denoise;
        c.seed = seed;
        return c;
    }

    public String fingerprint() {
        return String.join("|", engine, language, String.valueOf(voiceId), String.valueOf(style), String.valueOf(emotion),
                String.valueOf(speed), String.valueOf(expressiveness), String.valueOf(cfgValue),
                String.valueOf(inferenceTimesteps), String.valueOf(normalize), String.valueOf(denoise), String.valueOf(seed));
    }
}

package jmbe;

import jmbe.codec.RealFFT;
import jmbe.codec.ambe.AMBEAudioCodec;
import jmbe.codec.imbe.IMBEAudioCodec;
import jmbe.iface.IAudioCodec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocoderTest
{
    @Test
    void realFftRoundTripsAndMatchesTheDft()
    {
        Random random = new Random(1);
        int n = 256;
        float[] signal = new float[n];
        for(int i = 0; i < n; i++)
        {
            signal[i] = random.nextFloat() - 0.5f;
        }
        float[] packed = signal.clone();
        RealFFT fft = new RealFFT(n);
        fft.realForward(packed);

        // Bin 5 by direct DFT with exp(-2 pi i jk/n).
        double re = 0, im = 0;
        for(int j = 0; j < n; j++)
        {
            re += signal[j] * Math.cos(2 * Math.PI * 5 * j / n);
            im -= signal[j] * Math.sin(2 * Math.PI * 5 * j / n);
        }
        assertEquals(re, packed[10], 1e-3);
        assertEquals(im, packed[11], 1e-3);

        fft.realInverse(packed, true);
        for(int i = 0; i < n; i++)
        {
            assertEquals(signal[i], packed[i], 1e-4);
        }
    }

    @Test
    void imbeAndAmbeCodecsProduceAudio()
    {
        JMBEAudioLibrary library = new JMBEAudioLibrary();
        Random random = new Random(2);
        for(String name : new String[]{IMBEAudioCodec.CODEC_NAME, AMBEAudioCodec.CODEC_NAME})
        {
            IAudioCodec codec = library.getAudioConverter(name);
            assertNotNull(codec, name);
            int bytes = name.equals(IMBEAudioCodec.CODEC_NAME) ? 18 : 9;
            for(int frame = 0; frame < 50; frame++)
            {
                byte[] data = new byte[bytes];
                random.nextBytes(data);
                float[] audio = codec.getAudio(data);
                assertEquals(160, audio.length, name);
                for(float sample : audio)
                {
                    assertTrue(Float.isFinite(sample), name + " produced a non-finite sample");
                }
            }
        }
    }
}

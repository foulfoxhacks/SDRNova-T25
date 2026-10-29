/*
 * ******************************************************************************
 * Copyright (C) 2015-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */

package jmbe.codec.imbe;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import jmbe.codec.MBEModelParameters;
import jmbe.codec.MBEModelParametersLegacy;
import jmbe.codec.MBESynthesizer;
import jmbe.codec.MBESynthesizerLegacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * IMBE synthesizer for IMBE audio frames
 */
public class IMBESynthesizerLegacy extends MBESynthesizerLegacy
{
    private final static Logger mLog = LoggerFactory.getLogger(IMBESynthesizerLegacy.class);
    private IMBEModelParametersLegacy mPreviousParameters = new IMBEModelParametersLegacy();

    /**
     * Synthesizes 8 kHz 16-bit audio from IMBE audio frames
     */
    public IMBESynthesizerLegacy()
    {
        reset();
    }

    @Override
    public MBEModelParametersLegacy getPreviousFrame()
    {
        return mPreviousParameters;
    }

    public void reset()
    {
//        super.reset();
        mPreviousParameters = new IMBEModelParametersLegacy();
    }

    /**
     * Synthesizes 20 milliseconds of audio from the imbe frame parameters in
     * the following format:
     *
     * Sample Rate: 8 kHz
     * Sample Size: 16-bits
     * Frame Size: 160 samples
     * Bit Format: Little Endian
     *
     * @return ByteBuffer containing the audio sample bytes
     */
    public float[] getAudio(IMBEFrameLegacy frame)
    {
        IMBEModelParametersLegacy parameters = frame.getModelParameters(mPreviousParameters);

        float[] audio = null;

        if(parameters.isMaxFrameRepeat() || parameters.requiresMuting())
        {
            audio = getWhiteNoise();
        }
        else
        {
            audio = getVoice(parameters);
        }

        mPreviousParameters = parameters;

        return audio;
    }

    
}

/*
 * SDRNova T25 addition to JMBE (GPL-3.0): stands in for JTransforms' FloatFFT_1D so the codec has no external FFT
 * dependency on Android. Only the real transforms the synthesizers use are provided, with JTransforms' packed layout:
 * a[0] = Re[0], a[1] = Re[n/2], a[2k] = Re[k] and a[2k+1] = Im[k] for 0 < k < n/2. The forward transform uses
 * exp(-2 pi i jk/n), as JTransforms does.
 */
package jmbe.codec;

public class RealFFT
{
    private final int mSize;
    private final int mBits;
    private final float[] mRe;
    private final float[] mIm;
    private final float[] mCos;
    private final float[] mSin;

    public RealFFT(int size)
    {
        if(size < 2 || Integer.bitCount(size) != 1)
        {
            throw new IllegalArgumentException("FFT size must be a power of two: " + size);
        }

        mSize = size;
        mBits = Integer.numberOfTrailingZeros(size);
        mRe = new float[size];
        mIm = new float[size];
        mCos = new float[size / 2];
        mSin = new float[size / 2];

        for(int k = 0; k < size / 2; k++)
        {
            mCos[k] = (float)Math.cos(2 * Math.PI * k / size);
            mSin[k] = (float)Math.sin(2 * Math.PI * k / size);
        }
    }

    public void realForward(float[] a)
    {
        for(int i = 0; i < mSize; i++)
        {
            mRe[i] = a[i];
            mIm[i] = 0;
        }

        transform(false);

        int half = mSize / 2;
        a[0] = mRe[0];
        a[1] = mRe[half];

        for(int k = 1; k < half; k++)
        {
            a[2 * k] = mRe[k];
            a[2 * k + 1] = mIm[k];
        }
    }

    public void realInverse(float[] a, boolean scale)
    {
        int half = mSize / 2;
        mRe[0] = a[0];
        mIm[0] = 0;
        mRe[half] = a[1];
        mIm[half] = 0;

        for(int k = 1; k < half; k++)
        {
            mRe[k] = a[2 * k];
            mIm[k] = a[2 * k + 1];
            mRe[mSize - k] = a[2 * k];
            mIm[mSize - k] = -a[2 * k + 1];
        }

        transform(true);

        float factor = scale ? 1.0f / mSize : 1.0f;

        for(int i = 0; i < mSize; i++)
        {
            a[i] = mRe[i] * factor;
        }
    }

    private void transform(boolean inverse)
    {
        for(int i = 0; i < mSize; i++)
        {
            int j = Integer.reverse(i) >>> (32 - mBits);

            if(j > i)
            {
                float t = mRe[i];
                mRe[i] = mRe[j];
                mRe[j] = t;
                t = mIm[i];
                mIm[i] = mIm[j];
                mIm[j] = t;
            }
        }

        float sign = inverse ? 1.0f : -1.0f;

        for(int half = 1; half < mSize; half *= 2)
        {
            int stride = mSize / (half * 2);

            for(int start = 0; start < mSize; start += half * 2)
            {
                for(int k = 0; k < half; k++)
                {
                    float wr = mCos[k * stride];
                    float wi = sign * mSin[k * stride];
                    int a = start + k;
                    int b = a + half;
                    float tr = mRe[b] * wr - mIm[b] * wi;
                    float ti = mRe[b] * wi + mIm[b] * wr;
                    mRe[b] = mRe[a] - tr;
                    mIm[b] = mIm[a] - ti;
                    mRe[a] += tr;
                    mIm[a] += ti;
                }
            }
        }
    }
}

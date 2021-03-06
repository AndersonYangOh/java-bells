/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.codec.video.h263p;

import java.awt.*;

import javax.media.*;
import javax.media.format.*;

import net.sf.fmj.media.*;

import org.jitsi.impl.neomedia.codec.*;
import org.jitsi.impl.neomedia.codec.video.*;
import org.jitsi.service.neomedia.codec.*;

/**
 * Implements a H.263+ decoder.
 *
 * @author Sebastien Vincent
 * @author Lyubomir Marinov
 */
public class JNIDecoder
    extends AbstractCodec
{
    /**
     * The default output <tt>VideoFormat</tt>.
     */
    private static final VideoFormat[] DEFAULT_OUTPUT_FORMATS
        = new VideoFormat[] { new AVFrameFormat(FFmpeg.PIX_FMT_YUV420P) };

    /**
     * Plugin name.
     */
    private static final String PLUGIN_NAME = "H.263+ Decoder";

    /**
     *  The codec context native pointer we will use.
     */
    private long avcontext = 0;

    /**
     *  The decoded data is stored in avpicture in native ffmpeg format (YUV).
     */
    private long avframe = 0;

    /**
     * If decoder has got a picture.
     */
    private final boolean[] got_picture = new boolean[1];

    /**
     * The last known height of {@link #avcontext} i.e. the video output by this
     * <tt>JNIDecoder</tt>. Used to detect changes in the output size.
     */
    private int height = 0;

    /**
     * Array of output <tt>VideoFormat</tt>s.
     */
    private final VideoFormat[] outputFormats;

    /**
     * The last known width of {@link #avcontext} i.e. the video output by this
     * <tt>JNIDecoder</tt>. Used to detect changes in the output size.
     */
    private int width = 0;

    /**
     * Initializes a new <tt>JNIDecoder</tt> instance which is to decode H.263+
     * encoded data into frames in YUV format.
     */
    public JNIDecoder()
    {
        inputFormats = new VideoFormat[] { new VideoFormat(Constants.H263P) };
        outputFormats = DEFAULT_OUTPUT_FORMATS;
    }

    /**
     * Check <tt>Format</tt>.
     *
     * @param format <tt>Format</tt> to check
     * @return true if <tt>Format</tt> is H263P_RTP
     */
    public boolean checkFormat(Format format)
    {
        return format.getEncoding().equals(Constants.H263P_RTP);
    }

    /**
     * Close <tt>Codec</tt>.
     */
    @Override
    public synchronized void close()
    {
        if (opened)
        {
            opened = false;
            super.close();

            FFmpeg.avcodec_close(avcontext);
            FFmpeg.av_free(avcontext);
            avcontext = 0;

            FFmpeg.avcodec_free_frame(avframe);
            avframe = 0;
        }
    }

    /**
     * Ensure frame rate.
     *
     * @param frameRate frame rate
     * @return frame rate
     */
    private float ensureFrameRate(float frameRate)
    {
        return frameRate;
    }

    /**
     * Get matching outputs for a specified input <tt>Format</tt>.
     *
     * @param inputFormat input <tt>Format</tt>
     * @return array of matching outputs or null if there are no matching
     * outputs.
     */
    protected Format[] getMatchingOutputFormats(Format inputFormat)
    {
        VideoFormat inputVideoFormat = (VideoFormat) inputFormat;

        return
            new Format[]
            {
                new AVFrameFormat(
                        inputVideoFormat.getSize(),
                        ensureFrameRate(inputVideoFormat.getFrameRate()),
                        FFmpeg.PIX_FMT_YUV420P)
            };
    }

    /**
     * Get plugin name.
     *
     * @return "H.263+ Decoder"
     */
    @Override
    public String getName()
    {
        return PLUGIN_NAME;
    }

    /**
     * Get all supported output <tt>Format</tt>s.
     *
     * @param inputFormat input <tt>Format</tt> to determine corresponding
     * output <tt>Format/tt>s
     * @return array of supported <tt>Format</tt>
     */
    public Format[] getSupportedOutputFormats(Format inputFormat)
    {
        if (inputFormat == null)
            return outputFormats;

        // mismatch input format
        if (!(inputFormat instanceof VideoFormat)
                || (AbstractCodec2.matches(inputFormat, inputFormats) == null))
            return new Format[0];

        // match input format
        return getMatchingOutputFormats(inputFormat);
    }

    /**
     * Inits the codec instances.
     *
     * @throws ResourceUnavailableException if codec initialization failed
     */
    @Override
    public synchronized void open()
        throws ResourceUnavailableException
    {
        if (opened)
            return;

        /* from ffmpeg -formats output: "For example, the h263 decoder
         * corresponds to the h263 and h263p encoders". That's why we use
         * CODEC_ID_H263 for the decoder side (instead of CODEC_ID_H263P).
         */
        long avcodec = FFmpeg.avcodec_find_decoder(FFmpeg.CODEC_ID_H263);

        avcontext = FFmpeg.avcodec_alloc_context3(avcodec);
        FFmpeg.avcodeccontext_set_workaround_bugs(avcontext,
            FFmpeg.FF_BUG_AUTODETECT);

        if (FFmpeg.avcodec_open2(avcontext, avcodec) < 0)
            throw new RuntimeException("Could not open codec CODEC_ID_H263");

        avframe = FFmpeg.avcodec_alloc_frame();

        opened = true;
        super.open();
    }

    /**
     * Decodes H.263+ media data read from a specific input <tt>Buffer</tt> into
     * a specific output <tt>Buffer</tt>.
     *
     * @param in input <tt>Buffer</tt>
     * @param out output <tt>Buffer</tt>
     * @return <tt>BUFFER_PROCESSED_OK</tt> if <tt>in</tt> has been successfully
     * processed
     */
    public synchronized int process(Buffer in, Buffer out)
    {
        if (!checkInputBuffer(in))
            return BUFFER_PROCESSED_FAILED;
        if (isEOM(in) || !opened)
        {
            propagateEOM(out);
            return BUFFER_PROCESSED_OK;
        }
        if (in.isDiscard())
        {
            out.setDiscard(true);
            return BUFFER_PROCESSED_OK;
        }

        // Ask FFmpeg to decode.
        got_picture[0] = false;
        // TODO Take into account the offset of inputBuffer.
        FFmpeg.avcodec_decode_video(
                avcontext,
                avframe,
                got_picture,
                (byte[]) in.getData(), in.getLength());

        if (!got_picture[0])
        {
            out.setDiscard(true);
            return BUFFER_PROCESSED_OK;
        }

        // format
        int width = FFmpeg.avcodeccontext_get_width(avcontext);
        int height = FFmpeg.avcodeccontext_get_height(avcontext);

        if ((width > 0)
                && (height > 0)
                && ((this.width != width) || (this.height != height)))
        {
            this.width = width;
            this.height = height;

            // Output in same size and frame rate as input.
            Dimension outSize = new Dimension(this.width, this.height);
            VideoFormat inFormat = (VideoFormat) in.getFormat();
            float outFrameRate = ensureFrameRate(inFormat.getFrameRate());

            outputFormat
                = new AVFrameFormat(
                        outSize,
                        outFrameRate,
                        FFmpeg.PIX_FMT_YUV420P);
        }
        out.setFormat(outputFormat);

        // data
        Object outData = out.getData();

        if (!(outData instanceof AVFrame)
                || (((AVFrame) outData).getPtr() != avframe))
        {
            out.setData(new AVFrame(avframe));
        }

        // timeStamp
        long pts = FFmpeg.AV_NOPTS_VALUE; // TODO avframe_get_pts(avframe);

        if (pts == FFmpeg.AV_NOPTS_VALUE)
            out.setTimeStamp(Buffer.TIME_UNKNOWN);
        else
        {
            out.setTimeStamp(pts);

            int outFlags = out.getFlags();

            outFlags |= Buffer.FLAG_RELATIVE_TIME;
            outFlags &= ~(Buffer.FLAG_RTP_TIME | Buffer.FLAG_SYSTEM_TIME);
            out.setFlags(outFlags);
        }

        return BUFFER_PROCESSED_OK;
    }

    /**
     * Sets the <tt>Format</tt> of the media data to be input for processing in
     * this <tt>Codec</tt>.
     *
     * @param format the <tt>Format</tt> of the media data to be input for
     * processing in this <tt>Codec</tt>
     * @return the <tt>Format</tt> of the media data to be input for processing
     * in this <tt>Codec</tt> if <tt>format</tt> is compatible with this
     * <tt>Codec</tt>; otherwise, <tt>null</tt>
     */
    @Override
    public Format setInputFormat(Format format)
    {
        Format setFormat = super.setInputFormat(format);

        if (setFormat != null)
            reset();
        return setFormat;
    }
}

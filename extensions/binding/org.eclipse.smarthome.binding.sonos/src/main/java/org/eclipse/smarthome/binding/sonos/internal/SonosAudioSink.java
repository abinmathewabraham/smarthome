/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.sonos.internal;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.eclipse.smarthome.binding.sonos.handler.ZonePlayerHandler;
import org.eclipse.smarthome.core.audio.AudioFormat;
import org.eclipse.smarthome.core.audio.AudioHTTPServer;
import org.eclipse.smarthome.core.audio.AudioSink;
import org.eclipse.smarthome.core.audio.AudioStream;
import org.eclipse.smarthome.core.audio.FixedLengthAudioStream;
import org.eclipse.smarthome.core.audio.URLAudioStream;
import org.eclipse.smarthome.core.audio.UnsupportedAudioFormatException;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.net.HttpServiceUtil;
import org.eclipse.smarthome.core.net.NetUtil;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This makes a Sonos speaker to serve as an {@link AudioSink}-
 *
 * @author Kai Kreuzer - Initial contribution and API
 *
 */
public class SonosAudioSink implements AudioSink {

    private final Logger logger = LoggerFactory.getLogger(SonosAudioSink.class);

    private static HashSet<AudioFormat> supportedFormats = new HashSet<>();

    static {
        supportedFormats.add(AudioFormat.WAV);
        supportedFormats.add(AudioFormat.MP3);
    }

    private AudioHTTPServer audioHTTPServer;
    private ZonePlayerHandler handler;
    private BundleContext context;
    private String callbackUrl;

    public SonosAudioSink(BundleContext context, ZonePlayerHandler handler, AudioHTTPServer audioHTTPServer,
            String callbackUrl) {
        this.context = context;
        this.handler = handler;
        this.audioHTTPServer = audioHTTPServer;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public String getId() {
        return handler.getThing().getUID().toString();
    }

    @Override
    public String getLabel(Locale locale) {
        return handler.getThing().getLabel();
    }

    @Override
    public void process(AudioStream audioStream) throws UnsupportedAudioFormatException {
        if (audioStream instanceof URLAudioStream) {
            // it is an external URL, the speaker can access it itself and play it.
            URLAudioStream urlAudioStream = (URLAudioStream) audioStream;
            handler.playURI(new StringType(urlAudioStream.getURL()));
            try {
                audioStream.close();
            } catch (IOException e) {
            }
        } else {
            // we serve it on our own HTTP server and treat it as a notification
            if (!(audioStream instanceof FixedLengthAudioStream)) {
                // Note that we have to pass a FixedLengthAudioStream, since Sonos does multiple concurrent requests to
                // the AudioServlet, so a one time serving won't work.
                throw new UnsupportedAudioFormatException("Sonos can only handle FixedLengthAudioStreams.", null);
                // TODO: Instead of throwing an exception, we could ourselves try to wrap it into a
                // FixedLengthAudioStream, but this might be dangerous as we have no clue, how much data to expect from
                // the stream.
            } else {
                String relativeUrl = audioHTTPServer.serve((FixedLengthAudioStream) audioStream, 10).toString();
                String url = createAbsoluteUrl(relativeUrl);

                AudioFormat format = audioStream.getFormat();
                if (AudioFormat.WAV.isCompatible(format)) {
                    handler.playNotificationSoundURI(new StringType(url + ".wav"));
                } else if (AudioFormat.MP3.isCompatible(format)) {
                    if (handler.getThing().getStatus() == ThingStatus.ONLINE) {
                        handler.playNotificationSoundURI(new StringType(url + ".mp3"));
                    } else {
                        logger.warn("Sonos speaker '{}' is not online - status is {}", handler.getThing().getUID(),
                                handler.getThing().getStatus());
                    }
                } else {
                    throw new UnsupportedAudioFormatException("Sonos only supports MP3 or WAV.", format);
                }
            }
        }
    }

    private String createAbsoluteUrl(String relativeUrl) {
        if (callbackUrl != null) {
            return callbackUrl + relativeUrl;
        } else {
            final String ipAddress = NetUtil.getLocalIpv4HostAddress();
            if (ipAddress == null) {
                logger.warn("No network interface could be found.");
                return null;
            }

            // we do not use SSL as it can cause certificate validation issues.
            final int port = HttpServiceUtil.getHttpServicePort(context);
            if (port == -1) {
                logger.warn("Cannot find port of the http service.");
                return null;
            }

            return "http://" + ipAddress + ":" + port + relativeUrl;
        }
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return supportedFormats;
    }

    @Override
    public PercentType getVolume() {
        return handler.getNotificationSoundVolume();
    }

    @Override
    public void setVolume(PercentType volume) {
        handler.setNotificationSoundVolume(volume);
    }

}

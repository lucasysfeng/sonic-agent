/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.tests.ios.mjpeg.MjpegInputStream;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import net.coobird.thumbnailator.Thumbnails;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;

import static org.cloud.sonic.agent.tools.BytesTool.sendByte;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/ios/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class IOSScreenWSServer implements IIOSWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    @Value("${sonic.agent.port}")
    private int port;
    @Value("${sonic.agent.skipframe:5}")
    private int skipFrame;
    @Value("${sonic.agent.skipsameframe:10}")
    private int skipSameFrame;

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey,
                       @PathParam("udId") String udId, @PathParam("token") String token) throws InterruptedException {
        if (secretKey.length() == 0 || (!secretKey.equals(key)) || token.length() == 0) {
            log.info("Auth Failed!");
            return;
        }

        if (!SibTool.getDeviceList().contains(udId)) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }

        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, udId);

        int screenPort = 0;
        int wait = 0;
        while (wait < 120) {
            Integer p = IOSWSServer.screenMap.get(udId);
            if (p != null) {
                screenPort = p;
                break;
            }
            Thread.sleep(500);
            wait++;
        }
        if (screenPort == 0) {
            return;
        }
        int finalScreenPort = screenPort;
        log.info("lucasysfeng, udId: {}, screenPort: {}", udId, screenPort);
        new Thread(() -> {
            URL url;
            try {
                url = new URL("http://localhost:" + finalScreenPort);
            } catch (MalformedURLException e) {
                return;
            }
            log.info("lucasysfeng, udId: {}, url: {}", udId, url);
            MjpegInputStream mjpegInputStream = null;
            int waitMjpeg = 0;
            while (mjpegInputStream == null) {
                try {
                    mjpegInputStream = new MjpegInputStream(url.openStream());
                } catch (IOException e) {
                    log.info(e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.info(e.getMessage());
                    return;
                }
                waitMjpeg++;
                if (waitMjpeg >= 20) {
                    log.info("mjpeg server connect fail");
                    return;
                }
            }
            ByteBuffer bufferedImage;
            int frameCount = 0;
            int lastSendImageSize = 0;
            int imageSize = 0;
            long readCost = 0;
            long readStart = 0;
            long readEnd = 0;
            int sameCount = 1;
            while (true) {
                try {
                    readStart = System.currentTimeMillis();
                    if ((bufferedImage = mjpegInputStream.readFrameForByteBuffer()) == null) break;
                    readEnd = System.currentTimeMillis();
                    imageSize = bufferedImage.remaining();
                } catch (IOException e) {
                    log.info(e.getMessage());
                    break;
                }

                // 跳过连续相同长度的帧, 最多跳过skipSameFrame帧
                if ((imageSize == lastSendImageSize) && (sameCount % skipSameFrame != 0)) {
                    sameCount++;
                    continue;
                }
                sameCount = 1;

                // 通过跳过帧的方式来提高传输效率，根据配置决定每几帧传输1帧，默认每5帧传输1帧
                frameCount++;
                if (frameCount % skipFrame == 0) {
                    readCost = readEnd - readStart;
                    lastSendImageSize = imageSize;
                    long sendStart = System.currentTimeMillis();
                    sendByte(session, bufferedImage);
                    long sendEnd = System.currentTimeMillis();
                    long sendCost = sendEnd - sendStart;
                    // log.info("lucasysfeng, read cost:{}ms, send cost:{}ms, imagesize:{}bytes, url:{}",
                    //         readCost, sendCost, imageSize, url);
                }
            }
            try {
                mjpegInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info("screen done.");
        }).start();

        session.getUserProperties().put("schedule", ScheduleTool.schedule(() -> {
            log.info("time up!");
            if (session.isOpen()) {
                JSONObject errMsg = new JSONObject();
                errMsg.put("msg", "error");
                BytesTool.sendText(session, errMsg.toJSONString());
                exit(session);
            }
        }, BytesTool.remoteTimeout));
    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
    }

    private void exit(Session session) {
        synchronized (session) {
            ScheduledFuture<?> future = (ScheduledFuture<?>) session.getUserProperties().get("schedule");
            future.cancel(true);
            WebSocketSessionMap.removeSession(session);
            removeUdIdMapAndSet(session);
            try {
                session.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            log.info("{} : quit.", session.getUserProperties().get("id").toString());
        }
    }
    
    /**
     * Compress image to reduce size for faster transmission
     * @param imageBytes Original image bytes
     * @return Compressed image bytes
     */
    private byte[] compressImage(byte[] imageBytes) {
        try {
            // Convert byte array to BufferedImage
            ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
            BufferedImage image = ImageIO.read(bais);
            
            // Compress image with 50% quality
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(image)
                    .scale(1.0) // Keep original size
                    .outputQuality(0.5) // 50% quality to reduce size
                    .outputFormat("JPEG")
                    .toOutputStream(baos);
            
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to compress image", e);
            // Return original image if compression fails
            return imageBytes;
        }
    }
}
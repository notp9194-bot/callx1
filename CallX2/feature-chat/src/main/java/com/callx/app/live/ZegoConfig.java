package com.callx.app.live;

/**
 * ZegoCloud credentials.
 *
 * 👉 ZegoCloud console (https://console.zegocloud.com) se free project banao,
 *    wahan se AppID aur AppSign milega — neeche dono jagah daal do.
 *
 * AppSign 64-character hex string hota hai.
 */
public class ZegoConfig {

    // ZegoCloud AppID (from console.zegocloud.com)
    public static final long APP_ID = 1983063690L;

    // ZegoCloud AppSign (64-char hex string)
    public static final String APP_SIGN = "7d2e03040fd4cd2e736306730580f7a5c396fa35d453a96ea5655efad5b282ab";

    private ZegoConfig() {}
}

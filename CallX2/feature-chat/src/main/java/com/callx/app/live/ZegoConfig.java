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

    // TODO: Apna ZegoCloud AppID yahan daalo (long number, console se milega)
    public static final long APP_ID = 1983063690;

    // TODO: Apna ZegoCloud AppSign yahan daalo (64-char hex string, console se milega)
    public static final String APP_SIGN = "7d2e03040fd4cd2e736306730580f7a5c396fa35d453a96ea5655efad5b282ab";

    private ZegoConfig() {}
}

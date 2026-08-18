import re, os

DRAW_DIR = "/home/claude/work/core/src/main/res/drawable"

# Each entry: list of path dicts. Path dict keys: d (pathData), stroke(bool)->use stroke style, fill (for filled icons)
# style 'line' -> fillColor transparent, strokeColor placeholder, strokeWidth default 1.8
# style 'fill' -> fillColor placeholder (white), no stroke

ICONS = {
"ic_add_reels": [("line","M12,5v14M5,12h14")],
"ic_admin_panel": [("line","M12,3l7,3v5c0,4.5 -3,8.3 -7,9.5c-4,-1.2 -7,-5 -7,-9.5V6z"),("line","M9,12l2,2l4,-4")],
"ic_alternate_email": [("line","M12,12m-4,0a4,4 0,1 0,8 0a4,4 0,1 0,-8 0"),("line","M16,10v3.5a2.5,2.5 0,0 0,5 0V12a9,9 0,1 0,-3.5,7.13")],
"ic_arrow_back": [("line","M19,12H5M12,19l-7,-7l7,-7")],
"ic_attach": [("line","M21.44,11.05l-9.19,9.19a6,6 0,0 1,-8.49,-8.49l9.19,-9.19a4,4 0,0 1,5.66,5.66l-9.2,9.19a2,2 0,0 1,-2.83,-2.83l8.49,-8.48")],
"ic_audio": [("line","M9,18V5l12,-2v13"),("fill","M6,21m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"),("fill","M18,19m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0")],
"ic_back": [("line","M15,18l-6,-6l6,-6")],
"ic_block": [("line","M12,12m-9,0a9,9 0,1 0,18 0a9,9 0,1 0,-18 0"),("line","M5.64,5.64l12.72,12.72")],
"ic_bookmark": [("line","M19,21l-7,-5l-7,5V5c0,-1.1 0.9,-2 2,-2h10c1.1,0 2,0.9 2,2z")],
"ic_bookmark_filled": [("fill","M19,21l-7,-5l-7,5V5c0,-1.1 0.9,-2 2,-2h10c1.1,0 2,0.9 2,2z")],
"ic_call_notification": [("line","M22,16.92v3a2,2 0,0 1,-2.18,2a19.79,19.79 0,0 1,-8.63,-3.07a19.5,19.5 0,0 1,-6,-6a19.79,19.79 0,0 1,-3.07,-8.67A2,2 0,0 1,4.11,2h3a2,2 0,0 1,2,1.72c0.127,0.96 0.361,1.903 0.7,2.81a2,2 0,0 1,-0.45,2.11L8.09,9.91a16,16 0,0 0,6,6l1.27,-1.27a2,2 0,0 1,2.11,-0.45c0.907,0.339 1.85,0.573 2.81,0.7A2,2 0,0 1,22,16.92z")],
"ic_camera": [("line","M23,19a2,2 0,0 1,-2,2H3a2,2 0,0 1,-2,-2V8a2,2 0,0 1,2,-2h4l2,-3h6l2,3h4a2,2 0,0 1,2,2z"),("line","M12,17m-4,0a4,4 0,1 0,8 0a4,4 0,1 0,-8 0")],
"ic_cancel": [("line","M18,6L6,18M6,6l12,12")],
"ic_check_circle": [("fill","M12,2a10,10 0,1 0,0.01,0z"), ("line_white","M8,12l2.5,2.5L16,9")],
"ic_chevron_left": [("line","M15,18l-6,-6l6,-6")],
"ic_close": [("line","M18,6L6,18M6,6l12,12")],
"ic_comment_reel": [("line","M21,11.5a8.38,8.38 0,0 1,-0.9,3.8a8.5,8.5 0,0 1,-7.6,4.7a8.38,8.38 0,0 1,-3.8,-0.9L3,21l1.9,-5.7a8.38,8.38 0,0 1,-0.9,-3.8a8.5,8.5 0,0 1,4.7,-7.6a8.38,8.38 0,0 1,3.8,-0.9h0.5a8.48,8.48 0,0 1,8,8z")],
"ic_double_tick": [("line","M2,12l4,4L14,8"),("line","M9,12l4,4L21,8")],
"ic_double_tick_blue": [("line#2196F3","M2,12l4,4L14,8"),("line#2196F3","M9,12l4,4L21,8")],
"ic_download_reel": [("line","M12,3v12m0,0l-4,-4m4,4l4,-4"),("line","M4,17v2a2,2 0,0 0,2,2h12a2,2 0,0 0,2,-2v-2")],
"ic_eye_off": [("line","M17.94,17.94A10.07,10.07 0,0 1,12,20c-7,0 -11,-8 -11,-8a18.45,18.45 0,0 1,5.06,-5.94M9.9,4.24A9.12,9.12 0,0 1,12,4c7,0 11,8 11,8a18.5,18.5 0,0 1,-2.16,3.19m-6.72,-1.07a3,3 0,1 1,-4.24,-4.24"),("line","M1,1l22,22")],
"ic_favorite": [("fill","M20.84,4.61a5.5,5.5 0,0 0,-7.78 0L12,5.67l-1.06,-1.06a5.5,5.5 0,0 0,-7.78 7.78l1.06,1.06L12,21.23l7.78,-7.78 1.06,-1.06a5.5,5.5 0,0 0,0 -7.78z")],
"ic_favorite_border": [("line","M20.84,4.61a5.5,5.5 0,0 0,-7.78 0L12,5.67l-1.06,-1.06a5.5,5.5 0,0 0,-7.78 7.78l1.06,1.06L12,21.23l7.78,-7.78 1.06,-1.06a5.5,5.5 0,0 0,0 -7.78z")],
"ic_file": [("line","M14,2H6a2,2 0,0 0,-2,2v16a2,2 0,0 0,2,2h12a2,2 0,0 0,2,-2V8z"),("line","M14,2v6h6")],
"ic_flip": [("line","M17,1l4,4l-4,4"),("line","M3,11V9a4,4 0,0 1,4,-4h14"),("line","M7,23l-4,-4l4,-4"),("line","M21,13v2a4,4 0,0 1,-4,4H3")],
"ic_flip_camera": [("line","M17,1l4,4l-4,4"),("line","M3,11V9a4,4 0,0 1,4,-4h14"),("line","M7,23l-4,-4l4,-4"),("line","M21,13v2a4,4 0,0 1,-4,4H3")],
"ic_gallery": [("line","M19,3H5a2,2 0,0 0,-2,2v14a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2V5a2,2 0,0 0,-2,-2z"),("fill","M8.5,10m-1.5,0a1.5,1.5 0,1 0,3 0a1.5,1.5 0,1 0,-3 0"),("line","M21,15l-5,-5L5,21")],
"ic_ghost": [("line","M12,2a9,9 0,0 0,-9,9v11l3,-3l2.5,3L11,19l1,3l1,-3l2.5,3l2.5,-3l3,3V11A9,9 0,0 0,12,2z"),("fill","M9,10.5m-1.2,0a1.2,1.2 0,1 0,2.4 0a1.2,1.2 0,1 0,-2.4 0"),("fill","M15,10.5m-1.2,0a1.2,1.2 0,1 0,2.4 0a1.2,1.2 0,1 0,-2.4 0")],
"ic_gif": [("line","M4,7h3v10H4z"),("line","M11,17c-1.66,0 -3,-1.34 -3,-3v-4c0,-1.66 1.34,-3 3,-3h2"),("line","M17,17V7h4"),("line","M17,12h3")],
"ic_group": [("line","M17,21v-2a4,4 0,0 0,-4,-4H5a4,4 0,0 0,-4,4v2"),("line","M9,11m-4,0a4,4 0,1 0,8 0a4,4 0,1 0,-8 0"),("line","M23,21v-2a4,4 0,0 0,-3,-3.87"),("line","M16,3.13a4,4 0,0 1,0,7.75")],
"ic_heart": [("line","M20.84,4.61a5.5,5.5 0,0 0,-7.78 0L12,5.67l-1.06,-1.06a5.5,5.5 0,0 0,-7.78 7.78l1.06,1.06L12,21.23l7.78,-7.78 1.06,-1.06a5.5,5.5 0,0 0,0 -7.78z")],
"ic_heart_filled": [("fill#FF416C","M20.84,4.61a5.5,5.5 0,0 0,-7.78 0L12,5.67l-1.06,-1.06a5.5,5.5 0,0 0,-7.78 7.78l1.06,1.06L12,21.23l7.78,-7.78 1.06,-1.06a5.5,5.5 0,0 0,0 -7.78z")],
"ic_home": [("line","M3,12l9,-9l9,9"),("line","M5,10v10a1,1 0,0 0,1,1h3a1,1 0,0 0,1,-1v-6h4v6a1,1 0,0 0,1,1h3a1,1 0,0 0,1,-1V10")],
"ic_logout": [("line","M9,21H5a2,2 0,0 1,-2,-2V5a2,2 0,0 1,2,-2h4"),("line","M16,17l5,-5l-5,-5"),("line","M21,12H9")],
"ic_message_notification": [("line","M21,11.5a8.38,8.38 0,0 1,-0.9,3.8a8.5,8.5 0,0 1,-7.6,4.7a8.38,8.38 0,0 1,-3.8,-0.9L3,21l1.9,-5.7a8.38,8.38 0,0 1,-0.9,-3.8a8.5,8.5 0,0 1,4.7,-7.6a8.38,8.38 0,0 1,3.8,-0.9h0.5a8.48,8.48 0,0 1,8,8z")],
"ic_mic": [("line","M12,1a3,3 0,0 0,-3,3v8a3,3 0,0 0,6,0V4a3,3 0,0 0,-3,-3z"),("line","M19,10v2a7,7 0,0 1,-14,0v-2"),("line","M12,19v4"),("line","M8,23h8")],
"ic_more_vert": [("fill","M12,7m-1.8,0a1.8,1.8 0,1 0,3.6 0a1.8,1.8 0,1 0,-3.6 0"),("fill","M12,12m-1.8,0a1.8,1.8 0,1 0,3.6 0a1.8,1.8 0,1 0,-3.6 0"),("fill","M12,17m-1.8,0a1.8,1.8 0,1 0,3.6 0a1.8,1.8 0,1 0,-3.6 0")],
"ic_music_note": [("line","M9,18V5l12,-2v13"),("fill","M6,21m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"),("fill","M18,19m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0")],
"ic_notifications": [("line","M18,8a6,6 0,0 0,-12,0c0,7 -3,9 -3,9h18s-3,-2 -3,-9"),("line","M13.73,21a2,2 0,0 1,-3.46,0")],
"ic_pause": [("fill","M6,4h4v16H6z"),("fill","M14,4h4v16h-4z")],
"ic_pdf": [("line","M14,2H6a2,2 0,0 0,-2,2v16a2,2 0,0 0,2,2h12a2,2 0,0 0,2,-2V8z"),("line","M14,2v6h6"),("text","PDF")],
"ic_person": [("line","M20,21v-2a4,4 0,0 0,-4,-4H8a4,4 0,0 0,-4,4v2"),("line","M12,11m-4,0a4,4 0,1 0,8 0a4,4 0,1 0,-8 0")],
"ic_person_add": [("line","M16,21v-2a4,4 0,0 0,-4,-4H5a4,4 0,0 0,-4,4v2"),("line","M9,11m-4,0a4,4 0,1 0,8 0a4,4 0,1 0,-8 0"),("line","M20,8v6"),("line","M23,11h-6")],
"ic_phone": [("line","M22,16.92v3a2,2 0,0 1,-2.18,2a19.79,19.79 0,0 1,-8.63,-3.07a19.5,19.5 0,0 1,-6,-6a19.79,19.79 0,0 1,-3.07,-8.67A2,2 0,0 1,4.11,2h3a2,2 0,0 1,2,1.72c0.127,0.96 0.361,1.903 0.7,2.81a2,2 0,0 1,-0.45,2.11L8.09,9.91a16,16 0,0 0,6,6l1.27,-1.27a2,2 0,0 1,2.11,-0.45c0.907,0.339 1.85,0.573 2.81,0.7A2,2 0,0 1,22,16.92z")],
"ic_phone_off": [("line","M10.68,13.31a16,16 0,0 0,3.41,2.6l1.27,-1.27a2,2 0,0 1,2.11,-0.45c0.86,0.32 1.76,0.55 2.68,0.68A2,2 0,0 1,22,16.92v3a2,2 0,0 1,-2.18,2a19.5,19.5 0,0 1,-8.5,-3.02a19.8,19.8 0,0 1,-6,-6a19.5,19.5 0,0 1,-3,-8.5A2,2 0,0 1,4.11,2h3a2,2 0,0 1,2,1.72c0.13,0.9 0.36,1.79 0.68,2.65a2,2 0,0 1,-0.45,2.11z"),("line","M23,1L1,23")],
"ic_photo_library": [("line","M19,3H5a2,2 0,0 0,-2,2v14a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2V5a2,2 0,0 0,-2,-2z"),("fill","M8.5,10m-1.5,0a1.5,1.5 0,1 0,3 0a1.5,1.5 0,1 0,-3 0"),("line","M21,15l-5,-5L5,21")],
"ic_play": [("fill","M5,3l14,9l-14,9z")],
"ic_poll": [("line","M12,20V10"),("line","M18,20V4"),("line","M6,20v-4")],
"ic_poll_check_filled": [("fill","M12,2a10,10 0,1 0,0.01,0z"),("line_white","M8,12l2.5,2.5L16,9")],
"ic_poll_checkbox_filled": [("fill","M19,3H5a2,2 0,0 0,-2,2v14a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2V5a2,2 0,0 0,-2,-2z"),("line_white","M7,12l3,3l7,-7")],
"ic_poll_checkbox_unselected": [("line","M19,3H5a2,2 0,0 0,-2,2v14a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2V5a2,2 0,0 0,-2,-2z")],
"ic_poll_radio_unselected": [("line","M12,12m-9,0a9,9 0,1 0,18 0a9,9 0,1 0,-18 0")],
"ic_post_add": [("line","M12,5v14M5,12h14")],
"ic_record_delete": [("line","M3,6h18"),("line","M19,6v14a2,2 0,0 1,-2,2H7a2,2 0,0 1,-2,-2V6m3,0V4a2,2 0,0 1,2,-2h4a2,2 0,0 1,2,2v2"),("line","M10,11v6"),("line","M14,11v6")],
"ic_record_lock_closed": [("line","M19,11H5a2,2 0,0 0,-2,2v7a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2v-7a2,2 0,0 0,-2,-2z"),("line","M7,11V7a5,5 0,0 1,10,0v4")],
"ic_record_lock_open": [("line","M19,11H5a2,2 0,0 0,-2,2v7a2,2 0,0 0,2,2h14a2,2 0,0 0,2,-2v-7a2,2 0,0 0,-2,-2z"),("line","M7,11V7a5,5 0,0 1,9.9,-1")],
"ic_reel_camera": [("line","M17,10.5V7a1,1 0,0 0,-1,-1H4a1,1 0,0 0,-1,1v10a1,1 0,0 0,1,1h12a1,1 0,0 0,1,-1v-3.5l4,4v-11z")],
"ic_reel_create": [("line","M12,22C6.477,22 2,17.523 2,12C2,6.477 6.477,2 12,2C17.523,2 22,6.477 22,12C22,17.523 17.523,22 12,22Z"),("line","M12,8L12,16"),("line","M8,12L16,12")],
"ic_reel_creator": [("line","M3,18C3,16 7,14.5 12,14.5C17,14.5 21,16 21,18V20H3V18Z"),("line","M12,14.5C9.515,14.5 7.5,12.485 7.5,10C7.5,7.515 9.515,5.5 12,5.5C14.485,5.5 16.5,7.515 16.5,10C16.5,12.485 14.485,14.5 12,14.5Z"),("line","M18,8L22,8"),("line","M20,6L20,10")],
"ic_reel_explore": [("line","M12,2a10,10 0,1 0,0.01,0z"),("line","M16.24,7.76L14.12,14.12L7.76,16.24L9.88,9.88Z")],
"ic_reels": [("line","M15,10l4.55,-2.5A1,1 0,0 1,21,8.37v7.26a1,1 0,0 1,-1.45,0.89L15,14"),("line","M3,6h10a2,2 0,0 1,2,2v8a2,2 0,0 1,-2,2H3a2,2 0,0 1,-2,-2V8a2,2 0,0 1,2,-2z")],
"ic_reply": [("line","M9,14L4,9l5,-5"),("line","M20,20v-7a4,4 0,0 0,-4,-4H4")],
"ic_repost": [("line","M17,1l4,4l-4,4"),("line","M3,11V9a4,4 0,0 1,4,-4h14"),("line","M7,23l-4,-4l4,-4"),("line","M21,13v2a4,4 0,0 1,-4,4H3")],
"ic_schedule": [("line","M12,2a10,10 0,1 0,0.01,0z"),("line","M12,6v6l4,2")],
"ic_search": [("line","M11,19a8,8 0,1 0,0.01,0z"),("line","M21,21l-4.35,-4.35")],
"ic_send": [("line","M22,2L11,13"),("line","M22,2l-7,20l-4,-9l-9,-4z")],
"ic_send_fill": [("fill","M22,2L11,13l-2,8l2,-8l-9,-2z"),("fill","M22,2l-7,20l-4,-9l-9,-4z")],
"ic_share_reel": [("fill","M18,8m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"),("fill","M6,15m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"),("fill","M18,22m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"),("line","M8.59,13.51l6.83,3.98"),("line","M15.41,6.51l-6.82,3.98")],
"ic_shield": [("line","M12,22s8,-4 8,-10V5l-8,-3l-8,3v7c0,6 8,10 8,10z")],
"ic_single_tick": [("line","M4,12l5,5L20,6")],
"ic_status_add": [("line","M12,12m-9,0a9,9 0,1 0,18 0a9,9 0,1 0,-18 0"),("line","M12,8v8M8,12h8")],
"ic_status_notification": [("line","M12,2a10,10 0,1 0,0.01,0z"),("fill","M11,16m-1,0a1,1 0,1 0,2 0a1,1 0,1 0,-2 0"),("line","M12,7v6")],
"ic_sticker": [("line","M14.5,3H6.5A3.5,3.5 0,0 0,3,6.5v11A3.5,3.5 0,0 0,6.5,21h8.79a2,2 0,0 0,1.41,-0.59l3.71,-3.71A2,2 0,0 0,21,15.29V6.5A3.5,3.5 0,0 0,17.5,3z"),("line","M14,18.5V15a1,1 0,0 1,1,-1h3.5"),("fill","M8.5,9m-1,0a1,1 0,1 0,2 0a1,1 0,1 0,-2 0"),("fill","M13.5,9m-1,0a1,1 0,1 0,2 0a1,1 0,1 0,-2 0"),("line","M7.5,12.5c1,1.3 2.6,2 3.5,2s2.5,-0.7 3.5,-2")],
"ic_timer": [("line","M12,2a10,10 0,1 0,0.01,0z"),("line","M12,7v5l3,3")],
"ic_verified": [("fill","M12,1l2.09,2.26l3.02,-0.65l0.6,3.03l3.03,0.6l-0.65,3.02L22,12l-2.26,2.09l0.65,3.02l-3.03,0.6l-0.6,3.03l-3.02,-0.65L12,22l-2.09,-2.26l-3.02,0.65l-0.6,-3.03l-3.03,-0.6l0.65,-3.02L1,12l2.26,-2.09l-0.65,-3.02l3.03,-0.6l0.6,-3.03l3.02,0.65z"),("line_white","M8.5,12.5l2.5,2.5l4.5,-5")],
"ic_video": [("line","M23,7l-7,5l7,5V7z"),("line","M14,5H3a2,2 0,0 0,-2,2v10a2,2 0,0 0,2,2h11a2,2 0,0 0,2,-2V7a2,2 0,0 0,-2,-2z")],
"ic_video_call": [("line","M23,7l-7,5l7,5V7z"),("line","M14,5H3a2,2 0,0 0,-2,2v10a2,2 0,0 0,2,2h11a2,2 0,0 0,2,-2V7a2,2 0,0 0,-2,-2z")],
"ic_volume_off": [("line","M11,5L6,9H2v6h4l5,4z"),("line","M23,9l-6,6"),("line","M17,9l6,6")],
"ic_volume_on": [("line","M11,5L6,9H2v6h4l5,4z"),("line","M19.07,4.93a10,10 0,0 1,0,14.14"),("line","M15.54,8.46a5,5 0,0 1,0,7.07")],
}

def parse_attrs(text):
    w = re.search(r'android:width="([^"]+)"', text)
    h = re.search(r'android:height="([^"]+)"', text)
    t = re.search(r'android:tint="([^"]+)"', text)
    return (w.group(1) if w else "24dp", h.group(1) if h else "24dp", t.group(1) if t else None)

def build_paths(spec):
    out = []
    for kind, d in spec:
        if kind == "line":
            out.append(f'    <path\n        android:fillColor="@android:color/transparent"\n        android:pathData="{d}"\n        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="1.8"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"/>')
        elif kind == "fill":
            out.append(f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{d}"/>')
        elif kind.startswith("fill#"):
            color = kind.split("#",1)[1]
            out.append(f'    <path\n        android:fillColor="#{color}"\n        android:pathData="{d}"/>')
        elif kind.startswith("line#"):
            color = kind.split("#",1)[1]
            out.append(f'    <path\n        android:fillColor="@android:color/transparent"\n        android:pathData="{d}"\n        android:strokeColor="#{color}"\n        android:strokeWidth="1.8"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"/>')
        elif kind == "line_white":
            out.append(f'    <path\n        android:fillColor="@android:color/transparent"\n        android:pathData="{d}"\n        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="1.8"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"/>')
    return "\n".join(out)

changed = []
for name, spec in ICONS.items():
    path = os.path.join(DRAW_DIR, name + ".xml")
    if not os.path.exists(path):
        print("MISSING:", name)
        continue
    with open(path) as f:
        orig = f.read()
    w, h, tint = parse_attrs(orig)
    tint_attr = f'\n    android:tint="{tint}"' if tint else ""
    body = build_paths(spec)
    new_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}"
    android:height="{h}"
    android:viewportWidth="24"
    android:viewportHeight="24"{tint_attr}>
{body}
</vector>
'''
    with open(path, "w") as f:
        f.write(new_xml)
    changed.append(name)

print(f"Updated {len(changed)} icons")

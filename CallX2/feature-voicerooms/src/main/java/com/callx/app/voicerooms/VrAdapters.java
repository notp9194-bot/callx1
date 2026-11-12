package com.callx.app.voicerooms;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.voicerooms.VrModel.Gift;
import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Msg;
import com.callx.app.voicerooms.VrModel.Room;
import com.callx.app.voicerooms.VrModel.Track;

import de.hdodenhof.circleimageview.CircleImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * VrAdapters — Voice Rooms ke saare RecyclerView adapters ek jagah.
 * Sab adapters sirf UI dikhate hain; data ka source baad mein Firebase hoga.
 */
final class VrAdapters {
    private VrAdapters() {}

    /** Avatar bind: photoUrl null => initials. (Backend ke baad yahan Glide.) */
    static void bindAvatar(ImageView iv, Member m) {
        // TODO(backend): if (m.photoUrl != null) GlideLoader.load(iv, m.photoUrl, placeholder)
        iv.setImageDrawable(new InitialsDrawable(m.name));
    }

    // ───────────────────────────── Speakers ─────────────────────────────
    static class SpeakerAdapter extends RecyclerView.Adapter<SpeakerAdapter.VH> {
        interface OnClick { void onClick(Member m); }

        private final List<Member> items;
        private final OnClick click;
        private String speakingUid;

        SpeakerAdapter(List<Member> items, OnClick click) {
            this.items = items;
            this.click = click;
        }

        /** Demo: jis speaker ki ring "bol raha hai" wali state mein ho. */
        void setSpeaking(String uid) {
            String old = speakingUid;
            speakingUid = uid;
            for (int i = 0; i < items.size(); i++) {
                String id = items.get(i).uid;
                if (id.equals(old) || id.equals(uid)) notifyItemChanged(i);
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_speaker, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Member m = items.get(pos);
            boolean host = VrModel.ROLE_HOST.equals(m.role);
            boolean speaking = m.uid.equals(speakingUid) && !m.muted;

            h.name.setText(m.name);
            h.crown.setVisibility(host ? View.VISIBLE : View.GONE);
            h.hostPill.setVisibility(host ? View.VISIBLE : View.GONE);
            bindAvatar(h.avatar, m);
            h.mic.setImageResource(m.muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            h.ring.setBackgroundResource(speaking
                    ? R.drawable.vr_bg_ring_active : R.drawable.vr_bg_ring);
            float s = speaking ? 1.07f : 1f;
            h.ring.animate().scaleX(s).scaleY(s).setDuration(220).start();
            h.itemView.setOnClickListener(v -> { if (click != null) click.onClick(m); });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final FrameLayout ring;
            final CircleImageView avatar;
            final TextView name, crown, hostPill;
            final ImageView mic;

            VH(@NonNull View v) {
                super(v);
                ring = v.findViewById(R.id.vr_sp_ring);
                avatar = v.findViewById(R.id.vr_sp_avatar);
                name = v.findViewById(R.id.vr_sp_name);
                crown = v.findViewById(R.id.vr_sp_crown);
                hostPill = v.findViewById(R.id.vr_sp_host_pill);
                mic = v.findViewById(R.id.vr_sp_mic);
            }
        }
    }

    // ───────────────────────────── Audience grid ─────────────────────────────
    /** Layout: [Invite] + members... + [More]  (5 columns, screenshot jaisa). */
    static class AudienceAdapter extends RecyclerView.Adapter<AudienceAdapter.VH> {
        interface Callbacks {
            void onInvite();
            void onMember(Member m);
            void onMore();
        }

        private static final int T_INVITE = 0, T_MEMBER = 1, T_MORE = 2;

        private final List<Member> shown;
        private final boolean showMore;
        private final Callbacks cb;

        AudienceAdapter(List<Member> shown, boolean showMore, Callbacks cb) {
            this.shown = shown;
            this.showMore = showMore;
            this.cb = cb;
        }

        @Override public int getItemViewType(int position) {
            if (position == 0) return T_INVITE;
            if (showMore && position == shown.size() + 1) return T_MORE;
            return T_MEMBER;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_audience, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            int type = getItemViewType(pos);
            if (type == T_MEMBER) {
                Member m = shown.get(pos - 1);
                h.avatar.setVisibility(View.VISIBLE);
                h.icon.setVisibility(View.GONE);
                bindAvatar(h.avatar, m);
                h.online.setVisibility(m.online ? View.VISIBLE : View.GONE);
                h.name.setText(m.name);
                h.name.setTextColor(h.itemView.getContext().getColor(R.color.vr_text));
                h.itemView.setOnClickListener(v -> { if (cb != null) cb.onMember(m); });
            } else {
                boolean invite = type == T_INVITE;
                h.avatar.setVisibility(View.INVISIBLE);
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageResource(invite ? R.drawable.ic_add : R.drawable.ic_chevron_down);
                h.online.setVisibility(View.GONE);
                h.name.setText(invite ? "Invite" : "More");
                h.name.setTextColor(h.itemView.getContext().getColor(
                        invite ? R.color.vr_primary : R.color.vr_text_sub));
                h.itemView.setOnClickListener(v -> {
                    if (cb == null) return;
                    if (invite) cb.onInvite(); else cb.onMore();
                });
            }
        }

        @Override public int getItemCount() { return shown.size() + 1 + (showMore ? 1 : 0); }

        static class VH extends RecyclerView.ViewHolder {
            final CircleImageView avatar;
            final ImageView icon;
            final View online;
            final TextView name;

            VH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.vr_au_avatar);
                icon = v.findViewById(R.id.vr_au_icon);
                online = v.findViewById(R.id.vr_au_online);
                name = v.findViewById(R.id.vr_au_name);
            }
        }
    }

    // ───────────────────────────── Chat ─────────────────────────────
    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private final List<Msg> items;

        ChatAdapter(List<Msg> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_chat, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Msg m = items.get(pos);
            h.avatar.setImageDrawable(new InitialsDrawable(m.name));
            h.name.setText(m.name + " \u2022");
            h.time.setText(m.time);
            h.text.setText(m.text);
            h.hearts.setText(String.valueOf(m.hearts));
            h.hearts.setTextColor(h.itemView.getContext().getColor(
                    m.liked ? R.color.vr_primary : R.color.vr_text));
            h.react.setOnClickListener(v -> {
                // UI demo: local toggle. Backend: transaction on reactions counter.
                m.liked = !m.liked;
                m.hearts += m.liked ? 1 : -1;
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) notifyItemChanged(p);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final CircleImageView avatar;
            final TextView name, time, text, hearts;
            final View react;

            VH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.vr_ch_avatar);
                name = v.findViewById(R.id.vr_ch_name);
                time = v.findViewById(R.id.vr_ch_time);
                text = v.findViewById(R.id.vr_ch_text);
                hearts = v.findViewById(R.id.vr_ch_hearts);
                react = v.findViewById(R.id.vr_ch_react);
            }
        }
    }

    // ───────────────────────────── Home: room cards ─────────────────────────────
    static class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.VH> {
        interface OnClick { void onClick(Room r); }

        // Har card ka alag purple-family gradient (start, end)
        private static final int[][] GRADIENTS = {
            {0xFF8B5CF6, 0xFF3F1D9E},
            {0xFF7C3AED, 0xFFDB2777},
            {0xFF6366F1, 0xFF312E81},
            {0xFFA855F7, 0xFF581C87},
        };

        private final List<Room> items = new ArrayList<>();
        private final OnClick click;

        RoomAdapter(OnClick click) { this.click = click; }

        void submit(List<Room> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_room_card, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Room r = items.get(pos);
            int[] g = GRADIENTS[pos % GRADIENTS.length];
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{g[0], g[1]});
            bg.setCornerRadius(VrUtil.dp(h.itemView.getContext(), 22));
            h.root.setBackground(bg);

            h.listeners.setText(VrUtil.compact(r.listeners));
            h.title.setText(r.title);
            h.topic.setText(r.topic);
            h.host.setText("Hosted by " + r.hostName + (r.hostVerified ? "  \u2713" : ""));

            // overlapping speaker avatars (max 3)
            h.avatars.removeAllViews();
            int size = VrUtil.dp(h.itemView.getContext(), 30);
            int overlap = VrUtil.dp(h.itemView.getContext(), 9);
            int n = Math.min(3, r.speakers.size());
            for (int i = 0; i < n; i++) {
                CircleImageView iv = new CircleImageView(h.itemView.getContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                if (i > 0) lp.setMarginStart(-overlap);
                iv.setLayoutParams(lp);
                iv.setBorderWidth(VrUtil.dp(h.itemView.getContext(), 2));
                iv.setBorderColor(0xFFFFFFFF);
                bindAvatar(iv, r.speakers.get(i));
                h.avatars.addView(iv);
            }
            h.root.setOnClickListener(v -> { if (click != null) click.onClick(r); });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final FrameLayout root;
            final TextView listeners, title, topic, host;
            final LinearLayout avatars;

            VH(@NonNull View v) {
                super(v);
                root = v.findViewById(R.id.vr_rc_root);
                listeners = v.findViewById(R.id.vr_rc_listeners);
                title = v.findViewById(R.id.vr_rc_title);
                topic = v.findViewById(R.id.vr_rc_topic);
                host = v.findViewById(R.id.vr_rc_host);
                avatars = v.findViewById(R.id.vr_rc_avatars);
            }
        }
    }

    // ───────────────────────────── People sheet rows ─────────────────────────────
    static class PeopleAdapter extends RecyclerView.Adapter<PeopleAdapter.VH> {
        private final List<Member> items = new ArrayList<>();

        void submit(List<Member> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_participant, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Member m = items.get(pos);
            bindAvatar(h.avatar, m);
            h.name.setText(m.name);
            h.verified.setVisibility(m.verified ? View.VISIBLE : View.GONE);
            h.online.setVisibility(m.online ? View.VISIBLE : View.GONE);
            boolean speaker = !VrModel.ROLE_LISTENER.equals(m.role);
            h.role.setText(VrModel.ROLE_HOST.equals(m.role) ? "Host"
                    : speaker ? "Speaker" : (m.online ? "Listening" : "Away"));
            h.mic.setVisibility(speaker ? View.VISIBLE : View.GONE);
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final CircleImageView avatar;
            final View online;
            final TextView name, role;
            final ImageView verified, mic;

            VH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.vr_pp_avatar);
                online = v.findViewById(R.id.vr_pp_online);
                name = v.findViewById(R.id.vr_pp_name);
                role = v.findViewById(R.id.vr_pp_role);
                verified = v.findViewById(R.id.vr_pp_verified);
                mic = v.findViewById(R.id.vr_pp_mic);
            }
        }
    }

    // ───────────────────────────── Gifts ─────────────────────────────
    static class GiftAdapter extends RecyclerView.Adapter<GiftAdapter.VH> {
        interface OnSelect { void onSelect(Gift g); }

        private final List<Gift> items;
        private final OnSelect cb;
        private int selected = -1;

        GiftAdapter(List<Gift> items, OnSelect cb) {
            this.items = items;
            this.cb = cb;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_gift, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Gift g = items.get(pos);
            h.emoji.setText(g.emoji);
            h.name.setText(g.name);
            h.coins.setText("\uD83E\uDE99 " + g.coins);
            h.root.setBackgroundResource(pos == selected
                    ? R.drawable.vr_bg_gift_selected : R.drawable.vr_bg_gift);
            h.root.setOnClickListener(v -> {
                int old = selected;
                selected = h.getBindingAdapterPosition();
                if (old >= 0) notifyItemChanged(old);
                if (selected >= 0) notifyItemChanged(selected);
                if (cb != null && selected >= 0) cb.onSelect(items.get(selected));
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final View root;
            final TextView emoji, name, coins;

            VH(@NonNull View v) {
                super(v);
                root = v.findViewById(R.id.vr_gf_root);
                emoji = v.findViewById(R.id.vr_gf_emoji);
                name = v.findViewById(R.id.vr_gf_name);
                coins = v.findViewById(R.id.vr_gf_coins);
            }
        }
    }

    // ───────────────────────────── Music queue ─────────────────────────────
    static class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {
        interface OnClick { void onClick(Track t); }

        private final List<Track> items;
        private final OnClick cb;

        TrackAdapter(List<Track> items, OnClick cb) {
            this.items = items;
            this.cb = cb;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_vr_track, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Track t = items.get(pos);
            h.title.setText(t.title);
            h.artist.setText(t.artist);
            h.duration.setText(t.duration);
            h.itemView.setOnClickListener(v -> { if (cb != null) cb.onClick(t); });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, artist, duration;

            VH(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.vr_tr_title);
                artist = v.findViewById(R.id.vr_tr_artist);
                duration = v.findViewById(R.id.vr_tr_duration);
            }
        }
    }
}

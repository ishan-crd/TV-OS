package com.gathin.tvhome;

import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** One horizontal row of cards on the hero home: apps, or shows from Watch Next. */
final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    /** A card: an app, or a show (with the app that plays it). */
    static final class Item {
        final String key;
        final String title;
        /** Shown after the title under a focused card, e.g. "Today" or the app's name; may be null. */
        final String caption;
        final AppAdapter.Entry app;
        final WatchNext.Program show;
        /** A title from TMDB (Similar, Wishlist): a poster card that opens its details in the hero. */
        final Tmdb.Brief brief;

        Item(Tmdb.Brief b) {
            this.key = b.key();
            this.title = b.title;
            this.caption = b.year;
            this.app = null;
            this.show = null;
            this.brief = b;
        }

        Item(AppAdapter.Entry app, String caption) {
            this.key = app.component.flattenToShortString();
            this.title = app.label;
            this.caption = caption;
            this.app = app;
            this.show = null;
            this.brief = null;
        }

        Item(WatchNext.Program show, AppAdapter.Entry app) {
            this.key = show.key();
            this.title = show.title;
            this.caption = show.episode != null ? show.episode : app != null ? app.label : null;
            this.app = app;
            this.show = show;
            this.brief = null;
        }
    }

    interface Listener {
        void onCardFocused(int row, int position, Item item);

        /** A D-pad key on a card. Return true when handled. */
        boolean onCardKey(int row, int position, int keyCode);

        void onCardClick(Item item);

        void onCardLongClick(Item item, View card);
    }

    static final float FOCUS_SCALE = 1.1f;

    private final int row;
    private final Listener listener;
    private final List<Item> items = new ArrayList<>();

    /** Portrait poster cards (TMDB titles) instead of 16:9 ones. */
    private final boolean portrait;

    RowAdapter(int row, Listener listener) {
        this(row, listener, false);
    }

    RowAdapter(int row, Listener listener, boolean portrait) {
        this.row = row;
        this.listener = listener;
        this.portrait = portrait;
        setHasStableIds(true);
    }

    void setItems(List<Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    Item itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    int positionOf(String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) return i;
        return -1;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(portrait ? R.layout.item_hero_poster : R.layout.item_hero_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Item it = items.get(position);
        h.boundKey = it.key;
        h.name.setText(it.caption == null ? it.title : it.title + " · " + it.caption);
        boolean focused = h.card.isFocused();
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);

        if (it.brief != null) {
            h.progressTrack.setVisibility(View.GONE);
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.VISIBLE);
            showFallback(h, null, it.title);
            final String key = it.key;
            Posters.get(h.itemView.getContext()).load(Tmdb.IMG + "w342" + it.brief.poster, 240, 360, (uri, bmp) -> {
                if (bmp == null || !key.equals(h.boundKey)) return;
                h.banner.setImageBitmap(bmp);
                h.label.setVisibility(View.GONE);
            });
        } else if (it.show != null) {
            // Poster, loaded in the background; the app's icon and the title stand in until it arrives.
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.VISIBLE);
            showFallback(h, it.app != null ? it.app.icon : null, it.title);
            final String key = it.key;
            Posters.get(h.itemView.getContext()).load(it.show.posterUri, (uri, bmp) -> {
                if (bmp == null || !key.equals(h.boundKey)) return;
                h.banner.setImageBitmap(bmp);
                h.icon.setVisibility(View.GONE);
                h.label.setVisibility(View.GONE);
            });
            if (it.show.progress > 0.02f) {
                h.progressTrack.setVisibility(View.VISIBLE);
                h.progressTrack.post(() -> {
                    ViewGroup.LayoutParams lp = h.progressFill.getLayoutParams();
                    lp.width = Math.round(h.progressTrack.getWidth() * it.show.progress);
                    h.progressFill.setLayoutParams(lp);
                });
            } else {
                h.progressTrack.setVisibility(View.GONE);
            }
        } else {
            h.progressTrack.setVisibility(View.GONE);
            if (it.app.banner != null) {
                h.banner.setImageDrawable(it.app.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setVisibility(View.GONE);
                h.label.setVisibility(View.GONE);
            } else {
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                showFallback(h, it.app.icon, it.app.label);
            }
        }
        h.card.setOnClickListener(v -> listener.onCardClick(it));
        h.card.setOnLongClickListener(v -> {
            listener.onCardLongClick(it, v);
            return true;
        });
    }

    private static void showFallback(Holder h, android.graphics.drawable.Drawable icon, String text) {
        h.icon.setImageDrawable(icon);
        h.icon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        h.label.setText(text);
        h.label.setVisibility(View.VISIBLE);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card, progressTrack, progressFill;
        final ImageView banner, icon;
        final TextView label, name;
        String boundKey;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            progressTrack = v.findViewById(R.id.progressTrack);
            progressFill = v.findViewById(R.id.progressFill);
            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 9f * density);
                }
            });
            card.setClipToOutline(true);
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return pos != RecyclerView.NO_POSITION && listener.onCardKey(row, pos, keyCode);
            });
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? 0f : -4f * density)
                        .setDuration(200).start();
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) listener.onCardFocused(row, pos, items.get(pos));
            });
        }
    }
}

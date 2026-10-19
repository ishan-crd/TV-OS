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
import java.util.Map;

/** One horizontal row of app cards on the hero home. */
final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    interface Listener {
        void onCardFocused(int row, int position, AppAdapter.Entry e);

        /** A D-pad key on a card. Return true when handled. */
        boolean onCardKey(int row, int position, int keyCode);

        void onCardClick(AppAdapter.Entry e);

        void onCardLongClick(AppAdapter.Entry e);
    }

    static final float FOCUS_SCALE = 1.1f;

    private final int row;
    private final Listener listener;
    private final List<AppAdapter.Entry> items = new ArrayList<>();
    /** Optional text after the app name under a focused card, e.g. "Today"; keyed by package. */
    private Map<String, String> captions;

    RowAdapter(int row, Listener listener) {
        this.row = row;
        this.listener = listener;
        setHasStableIds(true);
    }

    void setItems(List<AppAdapter.Entry> list, Map<String, String> captions) {
        this.captions = captions;
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    AppAdapter.Entry itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hero_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        AppAdapter.Entry e = items.get(position);
        String caption = captions == null ? null : captions.get(e.pkg);
        h.name.setText(caption == null ? e.label : e.label + " · " + caption);
        boolean focused = h.card.isFocused();
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> listener.onCardClick(e));
        h.card.setOnLongClickListener(v -> {
            listener.onCardLongClick(e);
            return true;
        });
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner, icon;
        final TextView label, name;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
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

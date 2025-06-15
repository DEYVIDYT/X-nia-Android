package com.winlator.Download.adapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri; // Added for Uri.parse
import android.util.Log;
// import com.winlator.Download.DownloadManagerActivity; // Removed
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.R;
import com.winlator.Download.model.CommunityGame;
// import com.winlator.Download.service.DownloadService; // Removed

import java.util.List;

public class CommunityGamesAdapter extends RecyclerView.Adapter<CommunityGamesAdapter.ViewHolder> {

    private List<CommunityGame> gamesList;
    private Context context;

    public CommunityGamesAdapter(List<CommunityGame> gamesList, Context context) {
        this.gamesList = gamesList;
        this.context = context;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_community_game, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CommunityGame game = gamesList.get(position);
        
        holder.tvGameName.setText(game.getName());
        holder.tvGameSize.setText(game.getSize());
        
        holder.btnDownload.setOnClickListener(v -> {
            String gameUrl = game.getUrl();
            if (gameUrl != null && !gameUrl.isEmpty()) {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(gameUrl));
                    // Using holder.itemView.getContext() instead of the adapter's context field
                    // to ensure it's the most relevant context for starting an activity from an item view.
                    holder.itemView.getContext().startActivity(browserIntent);
                    Log.d("CommunityGamesAdapter", "Opening URL in browser. Game: '" + game.getName() + "', URL: '" + gameUrl + "'");
                } catch (Exception e) {
                    Log.e("CommunityGamesAdapter", "Could not open URL: " + gameUrl, e);
                    // Optionally, show a Toast to the user
                    // Toast.makeText(holder.itemView.getContext(), "Could not open link", Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.w("CommunityGamesAdapter", "Game URL is null or empty for: " + game.getName());
                // Optionally, show a Toast
                // Toast.makeText(holder.itemView.getContext(), "Download link is missing", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public int getItemCount() {
        return gamesList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGameName;
        TextView tvGameSize;
        Button btnDownload;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvGameName = itemView.findViewById(R.id.tv_game_name);
            tvGameSize = itemView.findViewById(R.id.tv_game_size);
            btnDownload = itemView.findViewById(R.id.btn_download);
        }
    }
}


package com.winlator.Download.adapter;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.winlator.Download.DownloadManagerActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.R;
import com.winlator.Download.model.CommunityGame;
import com.winlator.Download.service.DownloadService;

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
            // Iniciar download usando o DownloadService existente
            // Iniciar download usando o DownloadService existente
            Intent downloadIntent = new Intent(context, DownloadService.class);
            downloadIntent.putExtra("url", game.getUrl());
            downloadIntent.putExtra("filename", game.getName() + ".zip");
            Log.d("CommunityGamesAdapter", "Attempting to start DownloadService. Game: '" + game.getName() + "', URL: '" + game.getUrl() + "', Target Filename: '" + (game.getName() + ".zip") + "'");
            context.startForegroundService(downloadIntent);

            Intent activityIntent = new Intent(context, DownloadManagerActivity.class);
            context.startActivity(activityIntent);
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


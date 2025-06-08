package com.winlator.Download.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.R;
import com.winlator.Download.model.UploadStatus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UploadMonitorAdapter extends RecyclerView.Adapter<UploadMonitorAdapter.ViewHolder> {

    private List<UploadStatus> uploadsList;

    public UploadMonitorAdapter(List<UploadStatus> uploadsList) {
        this.uploadsList = uploadsList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_upload_status, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UploadStatus upload = uploadsList.get(position);
        
        holder.tvGameName.setText(upload.getGameName());
        holder.tvFileName.setText(upload.getFileName());
        holder.tvFileSize.setText(upload.getFormattedFileSize());
        holder.tvStatus.setText(upload.getStatusText());
        
        // Formatar tempo de início
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        holder.tvStartTime.setText("Iniciado às " + dateFormat.format(new Date(upload.getStartTime())));

        // Configurar progress bar
        if (upload.getStatus() == UploadStatus.Status.UPLOADING) {
            holder.progressBar.setVisibility(View.VISIBLE);
            holder.progressBar.setProgress(upload.getProgress());
            holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), android.R.color.holo_blue_dark));
        } else {
            holder.progressBar.setVisibility(View.GONE);
            
            if (upload.getStatus() == UploadStatus.Status.COMPLETED) {
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), android.R.color.holo_green_dark));
            } else if (upload.getStatus() == UploadStatus.Status.ERROR) {
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), android.R.color.holo_red_dark));
            }
        }
    }

    @Override
    public int getItemCount() {
        return uploadsList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGameName;
        TextView tvFileName;
        TextView tvFileSize;
        TextView tvStatus;
        TextView tvStartTime;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvGameName = itemView.findViewById(R.id.tv_upload_game_name);
            tvFileName = itemView.findViewById(R.id.tv_upload_file_name);
            tvFileSize = itemView.findViewById(R.id.tv_upload_file_size);
            tvStatus = itemView.findViewById(R.id.tv_upload_status);
            tvStartTime = itemView.findViewById(R.id.tv_upload_start_time);
            progressBar = itemView.findViewById(R.id.progress_upload);
        }
    }
}


package com.bg7yoz.ft8cn.ui;
/**
 * Message list Adapter. Used for decode interface, calling interface, grid tracker.
 * Different periods have different backgrounds. Total 4 background colors.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Locale;

public class CallingListAdapter extends RecyclerView.Adapter<CallingListAdapter.CallingListItemHolder> {
    public enum ShowMode{CALLING_LIST,MY_CALLING,TRACKER}
    private static final String TAG = "CallingListAdapter";
    private final MainViewModel mainViewModel;
    private final ArrayList<Ft8Message> ft8MessageArrayList;
    private final Context context;

    private final ShowMode showMode;
    private View.OnClickListener onItemClickListener;

    private final View.OnCreateContextMenuListener menuListener=new View.OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu contextMenu, View view, ContextMenu.ContextMenuInfo contextMenuInfo) {

            //view.setTag(ft8Message);//pass message object to upper level interface
            int postion= (int) view.getTag();
            if (postion==-1) return;
            if (postion>ft8MessageArrayList.size()-1) return;
            Ft8Message ft8Message=ft8MessageArrayList.get(postion);

            //add menu parameters i1:group, i2:id value, i3:display order
            if (!ft8Message.getCallsignTo().contains("...")//target cannot be self
                    //&& !ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (!ft8Message.checkIsCQ()) {
                    if (showMode==ShowMode.CALLING_LIST) {//in message list can show this menu
                        contextMenu.add(0, 0, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.tracking_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);
                    }
                    if (!mainViewModel.ft8TransmitSignal.isSynFrequency()) {//if same frequency, will affect transmitter!!!
                        contextMenu.add(0, 1, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.calling_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);
                    }
                    //means calling me, add reply menu
                    //if (ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)) {
                    if (GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())) {
                        contextMenu.add(0, 4, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.reply_to)
                                        , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                                .setActionView(view);

                    }
                    if (showMode!=ShowMode.TRACKER) {
                        contextMenu.add(0, 5, 0
                                , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                        , ft8Message.getCallsignTo())).setActionView(view);
                    }

                    //add query log
                    contextMenu.add(0, 7, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                    , ft8Message.getCallsignTo())).setActionView(view);

                }
            }

            if (!ft8Message.getCallsignFrom().contains("...")
                    //&& !ft8Message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignFrom())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (showMode==ShowMode.CALLING_LIST) {//in message list can show this menu
                    contextMenu.add(1, 2, 0, String.format(
                                    GeneralVariables.getStringFromResource(R.string.tracking)
                                    , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                            .setActionView(view);
                }
                contextMenu.add(1, 3, 0, String.format(
                                GeneralVariables.getStringFromResource(R.string.calling)
                                , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                        .setActionView(view);
                if (showMode!=ShowMode.TRACKER) {
                    contextMenu.add(1, 6, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                    , ft8Message.getCallsignFrom())).setActionView(view);
                }

                //add query log
                contextMenu.add(0, 8, 0
                        , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                , ft8Message.getCallsignFrom())).setActionView(view);
            }

        }
    };



    public CallingListAdapter(Context context, MainViewModel mainViewModel
            , ArrayList<Ft8Message> messages, ShowMode showMode) {
        this.mainViewModel = mainViewModel;
        this.context = context;
        this.showMode=showMode;
        ft8MessageArrayList = messages;
    }

    @NonNull
    @Override
    public CallingListItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        View view ;
        if (GeneralVariables.simpleCallItemMode) {
            view = layoutInflater.inflate(R.layout.call_list_holder_simple_item, parent, false);
        }else {
            view = layoutInflater.inflate(R.layout.call_list_holder_item, parent, false);
        }
        return new CallingListItemHolder(view,onItemClickListener,menuListener);
    }

    /**
     * Delete message
     *
     * @param position position in list
     */
    public void deleteMessage(int position) {
        if (position >= 0) {
            ft8MessageArrayList.remove(position);
        }
    }

    public Ft8Message getMessageByPosition(int position){
        if (ft8MessageArrayList==null) return null;
        if (position<0) return null;
        if (position>ft8MessageArrayList.size()-1) return null;
        return ft8MessageArrayList.get(position);
    }

    /**
     * Get message by holder
     *
     * @param holder holder
     * @return ft8message
     */
    public Ft8Message getMessageByViewHolder(RecyclerView.ViewHolder holder) {
        if (holder.getAdapterPosition() == -1) {
            return null;
        }
        return ft8MessageArrayList.get(holder.getAdapterPosition());
    }

    @SuppressLint("ResourceAsColor")
    @Override
    public void onBindViewHolder(@NonNull CallingListItemHolder holder, int position) {
        holder.callListHolderConstraintLayout.setTag(position);//set layout tag for message positioning
        holder.ft8Message = ft8MessageArrayList.get(position);
        holder.showMode = showMode;//determine if message list or follow message list
        holder.isSyncFreq = mainViewModel.ft8TransmitSignal.isSynFrequency();//if same freq transmit, do not show call receiver

        holder.callingUtcTextView.setText(UtcTimer.getTimeHHMMSS(holder.ft8Message.utcTime));
        //sequence, including color,
        holder.callingListSequenceTextView.setText(holder.ft8Message.getSequence() == 0 ? "0" : "1");
        holder.isWeakSignalImageView.setVisibility(holder.ft8Message.isWeakSignal ? View.VISIBLE:View.INVISIBLE);

        if (showMode==ShowMode.MY_CALLING) {//in calling interface
            holder.callingListSequenceTextView.setTextColor(context.getColor(R.color.follow_call_text_color));
        }

        //distinguish colors by 4 sequences within 1 minute
        switch (holder.ft8Message.getSequence4()) {
            case 0:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_0_style);
                break;
            case 1:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_1_style);
                break;
            case 2:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_2_style);
                break;
            case 3:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_3_style);
                break;
        }

        holder.callingListIdBTextView.setText(holder.ft8Message.getdB());
        //time offset, if exceeds 1.0 sec, -0.05 sec, red hint
        holder.callListDtTextView.setText(holder.ft8Message.getDt());
        if (holder.ft8Message.time_sec > 1.0f || holder.ft8Message.time_sec < -0.05) {
            holder.callListDtTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        } else {
            holder.callListDtTextView.setTextColor(context.getResources().getColor(
                    R.color.text_view_color));
        }


        holder.callingListFreqTextView.setText(holder.ft8Message.getFreq_hz());

        //check if callsign was QSLed, get existence in holder.otherBandIsQso
        setQueryHolderQSL_Callsign(holder);

        //if message related to my callsign
        if (holder.ft8Message.inMyCall()) {
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        } else if (holder.otherBandIsQso) {
            //set color for messages QSLed on other bands
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.fromcall_is_qso_text_color));
        } else {
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.message_text_color));
        }


        holder.callListMessageTextView.setText(holder.ft8Message.getMessageText(true));

        //carrier frequency
        holder.bandItemTextView.setText(BaseRigOperation.getFrequencyStr(holder.ft8Message.band));
        //calculate distance
        holder.callingListDistTextView.setText(MaidenheadGrid.getDistStr(
                GeneralVariables.getMyMaidenheadGrid()
                , holder.ft8Message.getMaidenheadGrid(mainViewModel.databaseOpr)));

        // === NEW: Calculate and display azimuth ===
        String myGrid = GeneralVariables.getMyMaidenheadGrid();
        String targetGrid = holder.ft8Message.getMaidenheadGrid(mainViewModel.databaseOpr);
        if (myGrid != null && !myGrid.isEmpty() && targetGrid != null && !targetGrid.isEmpty()) {
            double azimuth = MaidenheadGrid.getAzimuth(myGrid, targetGrid);
            if (azimuth >= 0) {
                holder.callingListAzimuthTextView.setText(String.format(Locale.US, "%.0f", azimuth));
            } else {
                holder.callingListAzimuthTextView.setText("--");
            }
        } else {
            holder.callingListAzimuthTextView.setText("--");
        }
        // === END NEW ===

        holder.callingListCallsignToTextView.setText("");//called station
        holder.callingListCallsignFromTextView.setText("");//calling station

        //message type
        holder.callingListCommandIInfoTextView.setText(holder.ft8Message.getCommandInfo());
        if (holder.ft8Message.i3 == 1 || holder.ft8Message.i3 == 2) {
            holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                    R.color.text_view_color));
        } else {
            holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        }

        //set CQ color
        if (holder.ft8Message.checkIsCQ()) {
            holder.callListMessageTextView.setBackgroundResource(R.color.textview_cq_color);
            holder.ft8Message.toWhere = "";
        } else {
            holder.callListMessageTextView.setBackgroundResource(R.color.textview_none_color);
        }


        if (holder.ft8Message.fromWhere != null) {
            holder.callingListCallsignFromTextView.setText(holder.ft8Message.fromWhere);
        } else {
            holder.callingListCallsignFromTextView.setText("");
        }

        if (holder.ft8Message.toWhere != null) {
            holder.callingListCallsignToTextView.setText(holder.ft8Message.toWhere);
        } else {
            holder.callingListCallsignToTextView.setText("");
        }

        //mark partitions not QSLed
        setToDxcc(holder);
        setFromDxcc(holder);


        //query callsign location, to avoid too much computation, only query when from is empty
//        if (holder.ft8Message.fromWhere == null) {
//            setQueryHolderCallsign(holder);//query callsign location
//        }

        if (holder.ft8Message.freq_hz <= 0.01f) {//this is transmit interface
            holder.callingListIdBTextView.setVisibility(View.GONE);
            holder.callListDtTextView.setVisibility(View.GONE);
            holder.callingListFreqTextView.setText("TX");
            holder.bandItemTextView.setVisibility(View.GONE);
            holder.callingListDistTextView.setVisibility(View.GONE);
            holder.callingListCommandIInfoTextView.setVisibility(View.GONE);
            holder.callingUtcTextView.setVisibility(View.GONE);
            holder.callingListCallsignToTextView.setVisibility(View.GONE);
            holder.callingListCallsignFromTextView.setVisibility(View.GONE);
            holder.dxccToImageView.setVisibility(View.GONE);
            holder.ituToImageView.setVisibility(View.GONE);
            holder.cqToImageView.setVisibility(View.GONE);
            holder.dxccFromImageView.setVisibility(View.GONE);
            holder.ituFromImageView.setVisibility(View.GONE);
            holder.cqFromImageView.setVisibility(View.GONE);
            // === NEW: Hide azimuth in TX mode ===
            holder.callingListAzimuthTextView.setVisibility(View.GONE);
            // === END NEW ===
        } else if (GeneralVariables.simpleCallItemMode){//simple list mode
            holder.bandItemTextView.setVisibility(View.GONE);
            holder.callingListDistTextView.setVisibility(View.GONE);
            holder.callingListCommandIInfoTextView.setVisibility(View.GONE);
            holder.callingUtcTextView.setVisibility(View.GONE);
            holder.callingListCallsignToTextView.setVisibility(View.GONE);
            holder.dxccToImageView.setVisibility(View.GONE);
            holder.ituToImageView.setVisibility(View.GONE);
            holder.cqToImageView.setVisibility(View.GONE);
            // === NEW: Hide azimuth in simple mode ===
            holder.callingListAzimuthTextView.setVisibility(View.GONE);
            // === END NEW ===
        }else {//standard list mode
            holder.callingListIdBTextView.setVisibility(View.VISIBLE);
            holder.callListDtTextView.setVisibility(View.VISIBLE);
            holder.bandItemTextView.setVisibility(View.VISIBLE);
            holder.callingListDistTextView.setVisibility(View.VISIBLE);
            holder.callingListCommandIInfoTextView.setVisibility(View.VISIBLE);
            holder.callingUtcTextView.setVisibility(View.VISIBLE);
            holder.callingListCallsignToTextView.setVisibility(View.VISIBLE);
            holder.callingListCallsignFromTextView.setVisibility(View.VISIBLE);
            // === NEW: Show azimuth in standard mode ===
            holder.callingListAzimuthTextView.setVisibility(View.VISIBLE);
            // === END NEW ===
        }
    }

    private void setFromDxcc(@NonNull CallingListItemHolder holder) {

        if (holder.ft8Message.fromDxcc && holder.ft8Message.freq_hz > 0.01f) {
            holder.dxccFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.dxccFromImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.fromCq && holder.ft8Message.freq_hz > 0.01f) {
            holder.cqFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.cqFromImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.fromItu && holder.ft8Message.freq_hz > 0.01f) {
            holder.ituFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.ituFromImageView.setVisibility(View.GONE);
        }
    }

    private void setToDxcc(@NonNull CallingListItemHolder holder) {
        if (holder.ft8Message.toDxcc && holder.ft8Message.freq_hz > 0.01f) {
            holder.dxccToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.dxccToImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.toCq && holder.ft8Message.freq_hz > 0.01f) {
            holder.cqToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.cqToImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.toItu && holder.ft8Message.freq_hz > 0.01f) {
            holder.ituToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.ituToImageView.setVisibility(View.GONE);
        }
    }

    //check if callsign was QSLed
    private void setQueryHolderQSL_Callsign(@NonNull CallingListItemHolder holder) {
        //check if QSLed on this band
        if (GeneralVariables.checkQSLCallsign(holder.ft8Message.getCallsignFrom())) {//if in database, strike through
            holder.callListMessageTextView.setPaintFlags(
                    holder.callListMessageTextView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {//if not in database, remove strike through
            holder.callListMessageTextView.setPaintFlags(
                    holder.callListMessageTextView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
        }
        holder.otherBandIsQso = GeneralVariables.checkQSLCallsign_OtherBand(holder.ft8Message.getCallsignFrom());
    }

    @Override
    public int getItemCount() {
        return ft8MessageArrayList.size();
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
    }

    static class CallingListItemHolder extends RecyclerView.ViewHolder {
        private static final String TAG = "CallingListItemHolder";
        ConstraintLayout callListHolderConstraintLayout;
        TextView callingListIdBTextView, callListDtTextView, callingListFreqTextView,
                callListMessageTextView, callingListDistTextView, callingListSequenceTextView,
                callingListCallsignFromTextView, callingListCallsignToTextView
                , callingListCommandIInfoTextView,
                bandItemTextView, callingUtcTextView,
                callingListAzimuthTextView; // === NEW: Azimuth TextView ===
        ImageView dxccToImageView, ituToImageView, cqToImageView, dxccFromImageView
                , ituFromImageView, cqFromImageView,isWeakSignalImageView;
        public Ft8Message ft8Message;
        //boolean showFollow;
        ShowMode showMode;
        boolean isSyncFreq;
        boolean otherBandIsQso = false;


        public CallingListItemHolder(@NonNull View itemView, View.OnClickListener listener
                ,View.OnCreateContextMenuListener menuListener) {
            super(itemView);
            callListHolderConstraintLayout = itemView.findViewById(R.id.callListHolderConstraintLayout);
            callingListIdBTextView = itemView.findViewById(R.id.callingListIdBTextView);
            callListDtTextView = itemView.findViewById(R.id.callListDtTextView);
            callingListFreqTextView = itemView.findViewById(R.id.callingListFreqTextView);
            callListMessageTextView = itemView.findViewById(R.id.callListMessageTextView);
            callingListDistTextView = itemView.findViewById(R.id.callingListDistTextView);
            callingListSequenceTextView = itemView.findViewById(R.id.callingListSequenceTextView);
            callingListCallsignFromTextView = itemView.findViewById(R.id.callingListCallsignFromTextView);
            callingListCallsignToTextView = itemView.findViewById(R.id.callToItemTextView);
            callingListCommandIInfoTextView = itemView.findViewById(R.id.callingListCommandIInfoTextView);
            bandItemTextView = itemView.findViewById(R.id.bandItemTextView);
            callingUtcTextView = itemView.findViewById(R.id.callingUtcTextView);
            // === NEW: Find azimuth TextView ===
            callingListAzimuthTextView = itemView.findViewById(R.id.callingListAzimuthTextView);
            // === END NEW ===

            dxccToImageView = itemView.findViewById(R.id.dxccToImageView);
            ituToImageView = itemView.findViewById(R.id.ituToImageView);
            cqToImageView = itemView.findViewById(R.id.cqToImageView);
            dxccFromImageView = itemView.findViewById(R.id.dxccFromImageView);
            ituFromImageView = itemView.findViewById(R.id.ituFromImageView);
            cqFromImageView = itemView.findViewById(R.id.cqFromImageView);
            isWeakSignalImageView=itemView.findViewById(R.id.isWeakSignalImageView);

            dxccToImageView.setVisibility(View.GONE);
            ituToImageView.setVisibility(View.GONE);
            cqToImageView.setVisibility(View.GONE);
            dxccFromImageView.setVisibility(View.GONE);
            ituFromImageView.setVisibility(View.GONE);
            cqFromImageView.setVisibility(View.GONE);
            itemView.setTag(-1);
            itemView.setOnClickListener(listener);
            itemView.setOnCreateContextMenuListener(menuListener);

        }


    }
}
 package com.example.tcptemplate;

import android.os.Bundle;
import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.design.activity.RobotActivity;
import com.aldebaran.qi.sdk.object.actuation.FreeFrame;
import com.aldebaran.qi.sdk.object.actuation.LookAtMovementPolicy;
import com.aldebaran.qi.sdk.object.actuation.Mapping;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.builder.LookAtBuilder;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.object.geometry.Vector3;
import com.aldebaran.qi.sdk.object.actuation.LookAt;
import com.aldebaran.qi.sdk.builder.TransformBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;


 public class MainActivity extends RobotActivity implements RobotLifecycleCallbacks {
     private String pepper = "Pepperくん";  // どっちに送るかに応じてコメントアウトする
//     private String pepper = "Pepperちゃん";

     private String mode = "lookspeaker";  // 話しているエージェントの方向を見るモード
//     private String mode = "lookliker";  // 関係性が+の方を見るモード

    int portNum = 2002;
     private LookAt lookAt;
     private Future<Void> lookAtFuture;
     private ScheduledFuture<?> lookAtHandle = null;
     private ScheduledExecutorService scheduler = null;

     @Override
     protected void onCreate(Bundle savedInstanceState) {
         super.onCreate(savedInstanceState);
         setContentView(R.layout.activity_main);
         QiSDK.register(this, this);
     }

     @Override
     protected void onDestroy() {
         super.onDestroy();
         QiSDK.unregister(this, this);
     }

     @Override
     public void onRobotFocusGained(QiContext qiContext) {
         SayBuilder.with(qiContext).withText("準備完了").build().run();
         System.out.println("プログラム起動");
         TCPServer(qiContext);
     }

     private void TCPServer(QiContext qiContext) {
         try (ServerSocket server = new ServerSocket(portNum)) {
             while (true) {
                 // -----------------------------------------
                 // 2.クライアントからの接続を待ち受け（accept）
                 // -----------------------------------------
                 Socket sc = null;
                 BufferedReader reader = null;
                 PrintWriter writer = null;
                 try {
                     sc = server.accept();
                     Log.d("TCP status", "connected");
                     reader = new BufferedReader(new InputStreamReader(sc.getInputStream()));
                     writer = new PrintWriter(sc.getOutputStream(), true);

                     String line;
                     while ((line = reader.readLine()) != null) {
                         if (line.startsWith("say:")) {
                             // 喋るメッセージの読み取り（改行まで）
                             String toSay = line.substring(4);
                             // もしpassだった場合はしゃべらない
                             if(toSay.equals("pass")){
                                 System.out.println("Skip speaking");
                             }else{
                                 System.out.println("Received toSay: " + toSay);
                                 Say say = SayBuilder.with(qiContext)
                                         .withText(toSay)
                                         .build();
                                 say.run();
                                 System.out.println("finished speaking");
                             }
                             // 「Say」アクションが終了したら、クライアントに「話し終わった」ことを通知
                             writer.println("Finished speaking");
                         }else if(line.startsWith("look:")){
                             // 関係性が+の方を見るモード
                             if(mode.equals("lookliker")) {
                                 // 視線用メッセージの受け取り（改行まで）
                                 String jsonMessage = line.substring(5);
                                 // JSONオブジェクトに変換し、値を取得
                                 JSONObject jsonObject = new JSONObject(jsonMessage);
                                 System.out.println("Received JSON: " + jsonObject);
                                 // JSONオブジェクトからデータを取り出す
                                 String relation1 = jsonObject.getString("康太と太郎の関係");
                                 String relation2 = jsonObject.getString("康太と花子の関係");
                                 String relation3 = jsonObject.getString("太郎と花子の関係");

                                 // Pepperが見るべき方向の変換を計算
                                 // ここでは、右または左に30度の角度で向くように設定
                                 double x = 1.732;
                                 double y = 0;
                                 double z = 1.1;
                                 if (pepper.equals("Pepperくん")) {
                                     if (relation1.equals("+")) {
                                         y = -1; //　最初は右30度。++の場合だけあとで制御
                                     } else if (relation1.equals("-") && relation3.equals("+")) {
                                         y = 1; //　左30度
                                     }
                                 } else if (pepper.equals("Pepperちゃん")) {
                                     if (relation2.equals("+")) {
                                         y = 1; //　最初は左30度。++の場合だけあとで制御
                                     } else if (relation2.equals("-") && relation3.equals("+")) {
                                         y = -1; //　右30度
                                     }
                                 }

                                 if (lookAtHandle != null && !lookAtHandle.isCancelled()) {
                                     lookAtHandle.cancel(true);
                                     Log.i("MainActivity", "LookAtHandle is canceled.");
                                     if (scheduler != null && !scheduler.isShutdown()) {
                                         scheduler.shutdown();
                                         Log.i("MainActivity", "scheduler is shutdowned.");
                                     }
                                 }

                                 // Pepperのロボットフレームを取得
                                 Frame robotFrame = qiContext.getActuation().robotFrame();
                                 Transform transform = TransformBuilder.create().fromTranslation(new Vector3(x, y, z));
                                 // Get the Mapping service from the QiContext.
                                 Mapping mapping = qiContext.getMapping();
                                 // Create a FreeFrame with the Mapping service.
                                 FreeFrame targetFrame = mapping.makeFreeFrame();
                                 // Update the target location relatively to Pepper's current location.
                                 targetFrame.update(robotFrame, transform, 0L);

                                 // 新しいLookAtアクションを開始する前に、以前のアクションをキャンセルする。
                                 if (lookAtFuture != null && !lookAtFuture.isDone()) {
                                     // 以前のアクションがまだ実行中の場合、キャンセルをリクエストする。
                                     Log.i("LookAtFuture", "Requesting cancellation of the ongoing LookAt action.");
                                     lookAtFuture.requestCancellation();
                                 }

                                 // LookAtアクションをビルド
                                 lookAt = LookAtBuilder.with(qiContext)
                                         .withFrame(targetFrame.frame())
                                         .build();
                                 // 頭だけを動かす
                                 lookAt.setPolicy(LookAtMovementPolicy.HEAD_ONLY);
                                 // LookAtの実行
                                 lookAt.addOnStartedListener(() -> Log.i("MainActivity", "LookAt action started."));
                                 lookAtFuture = lookAt.async().run();

                                 // ログ表示のためのスレッド
                                 new Thread(() -> {
                                     try {
                                         // Futureが完了するのを待ちます。この呼び出しはブロッキングされます。
                                         lookAtFuture.get(); // 'get' は結果を返すか、エラーが発生した場合は例外をスローします。

                                         // Futureが成功した場合、結果をログに出力します。
                                         Log.i("LookAtFuture", "LookAt action completed successfully.");
                                     } catch (CancellationException e) {
                                         // Futureがキャンセルされた場合の処理
                                         Log.i("LookAtFuture", "LookAt action was cancelled.");
                                     } catch (ExecutionException e) {
                                         // Futureの実行中に例外が発生した場合の処理をここに記述します。
                                         Throwable cause = e.getCause(); // 実際の原因を取得
                                         Log.e("LookAtFuture", "Exception in LookAt action: ", cause);
                                     } finally {
                                         // 例外が発生した場合でもリソースをクリーンアップ
                                         if (lookAt != null) {
                                             lookAt.removeAllOnStartedListeners();
                                             Log.i("MainActivity", "removeAllOnStartedListeners！");
                                         }
                                     }
                                 }).start();

                                 // 両方に+ならばスケジューラで5秒ごとに交互に向く
                                 if ((pepper.equals("Pepperくん") && relation1.equals("+") && relation3.equals("+")) || (pepper.equals("Pepperちゃん") && relation2.equals("+") && relation3.equals("+"))) {
                                     scheduler = Executors.newSingleThreadScheduledExecutor();

                                     // 実行するタスクを定義
                                     Runnable lookAtTask = new Runnable() {
                                         private boolean lookRight;

                                         {
                                             // 初期化ブロックでlookRightの初期値を設定
                                             // Pepperくんなら初期状態は右を向く。pepperちゃんなら初期状態は左を向く。
                                             lookRight = pepper.equals("Pepperくん");
                                         }

                                         @Override
                                         public void run() {
                                             // 前のLookAtアクションがあればキャンセル
                                             if (lookAtFuture != null && !lookAtFuture.isDone()) {
                                                 lookAtFuture.requestCancellation();
                                             }

                                             // 右または左に30度の方向を設定
                                             double angleY = lookRight ? -1 : 1; // 右か左か
                                             Transform directionTransform = TransformBuilder.create().fromTranslation(new Vector3(x, angleY, z));

                                             // FreeFrameを更新して新しい方向を設定
                                             targetFrame.update(robotFrame, directionTransform, 0L);

                                             // LookAtアクションをビルドして実行
                                             lookAt = LookAtBuilder.with(qiContext)
                                                     .withFrame(targetFrame.frame())
                                                     .build();
                                             lookAt.setPolicy(LookAtMovementPolicy.HEAD_ONLY);
                                             lookAtFuture = lookAt.async().run();

                                             // 次回は反対方向を向く
                                             lookRight = !lookRight;
                                         }
                                     };

                                     // タスクを初めて実行し、その後5秒ごとに繰り返す
                                     lookAtHandle = scheduler.scheduleAtFixedRate(lookAtTask, 0, 5, TimeUnit.SECONDS);
                                 }
                             // 話しているエージェントの方向を見るモード
                             }else if(mode.equals("lookspeaker")) {
                                 // 視線用メッセージの受け取り（改行まで）
                                 String speaking_agent = line.substring(5);

                                 // Pepperが見るべき方向の変換を計算
                                 // ここでは、右または左に30度の角度で向くように設定
                                 double x = 1.732;
                                 double y = 0;  //　自分が話す時はまっすぐ向いとく
                                 double z = 1.1;
                                 if (pepper.equals("Pepperくん")) {
                                     if (speaking_agent.equals("人間")) {
                                         y = -1; //　右30度
                                     } else if (speaking_agent.equals("Pepperちゃん")) {
                                         y = 1; //　左30度
                                     }
                                 } else if (pepper.equals("Pepperちゃん")) {
                                     if (speaking_agent.equals("人間")) {
                                         y = 1; //　左30度
                                     } else if (speaking_agent.equals("Pepperくん")) {
                                         y = -1; //　右30度
                                     }
                                 }

                                 if (lookAtHandle != null && !lookAtHandle.isCancelled()) {
                                     lookAtHandle.cancel(true);
                                     Log.i("MainActivity", "LookAtHandle is canceled.");
                                     if (scheduler != null && !scheduler.isShutdown()) {
                                         scheduler.shutdown();
                                         Log.i("MainActivity", "scheduler is shutdowned.");
                                     }
                                 }

                                 // Pepperのロボットフレームを取得
                                 Frame robotFrame = qiContext.getActuation().robotFrame();
                                 Transform transform = TransformBuilder.create().fromTranslation(new Vector3(x, y, z));
                                 // Get the Mapping service from the QiContext.
                                 Mapping mapping = qiContext.getMapping();
                                 // Create a FreeFrame with the Mapping service.
                                 FreeFrame targetFrame = mapping.makeFreeFrame();
                                 // Update the target location relatively to Pepper's current location.
                                 targetFrame.update(robotFrame, transform, 0L);

                                 // 新しいLookAtアクションを開始する前に、以前のアクションをキャンセルする。
                                 if (lookAtFuture != null && !lookAtFuture.isDone()) {
                                     // 以前のアクションがまだ実行中の場合、キャンセルをリクエストする。
                                     Log.i("LookAtFuture", "Requesting cancellation of the ongoing LookAt action.");
                                     lookAtFuture.requestCancellation();
                                 }

                                 // LookAtアクションをビルド
                                 lookAt = LookAtBuilder.with(qiContext)
                                         .withFrame(targetFrame.frame())
                                         .build();
                                 // 頭だけを動かす
                                 lookAt.setPolicy(LookAtMovementPolicy.HEAD_ONLY);
                                 // LookAtの実行
                                 lookAt.addOnStartedListener(() -> Log.i("MainActivity", "LookAt action started."));
                                 lookAtFuture = lookAt.async().run();

                                 // ログ表示のためのスレッド
                                 new Thread(() -> {
                                     try {
                                         // Futureが完了するのを待ちます。この呼び出しはブロッキングされます。
                                         lookAtFuture.get(); // 'get' は結果を返すか、エラーが発生した場合は例外をスローします。

                                         // Futureが成功した場合、結果をログに出力します。
                                         Log.i("LookAtFuture", "LookAt action completed successfully.");
                                     } catch (CancellationException e) {
                                         // Futureがキャンセルされた場合の処理
                                         Log.i("LookAtFuture", "LookAt action was cancelled.");
                                     } catch (ExecutionException e) {
                                         // Futureの実行中に例外が発生した場合の処理をここに記述します。
                                         Throwable cause = e.getCause(); // 実際の原因を取得
                                         Log.e("LookAtFuture", "Exception in LookAt action: ", cause);
                                     } finally {
                                         // 例外が発生した場合でもリソースをクリーンアップ
                                         if (lookAt != null) {
                                             lookAt.removeAllOnStartedListeners();
                                             Log.i("MainActivity", "removeAllOnStartedListeners！");
                                         }
                                     }
                                 }).start();
                             }
                         }
                     }
                 } catch (Exception e) {
                     System.out.println("Catch error");
                     e.printStackTrace();
                 } finally {
                     try {
                         if (writer != null) {
                             writer.close();
                         }
                         if (reader != null) {
                             reader.close();
                         }
                         if (sc != null) {
                             sc.close();
                         }
                         System.out.println("Resources are cleaned up.");
                     } catch (IOException e) {
                         e.printStackTrace();
                     }
                 }
             }
         } catch (Exception e) {
             System.out.println("Catch error3");
             e.printStackTrace();
         }
     }

     @Override
     public void onRobotFocusLost() {
     }

     @Override
     public void onRobotFocusRefused(String reason) {

     }
 }

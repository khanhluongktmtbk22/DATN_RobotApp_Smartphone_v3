# RobotApp – ngữ cảnh kỹ thuật hiện tại

> Cập nhật theo code ngày 31/08/2026. Đây là tài liệu định hướng để đọc và sửa dự án nhanh; khi tài liệu và code khác nhau, code đang chạy là nguồn sự thật.

## 1. Vai trò

RobotApp được cài trên tablet gắn trên robot và là bộ não chính:

- nhận cấu hình, lệnh điều khiển và tín hiệu cuộc gọi từ Firebase;
- nhận diện người cao tuổi bằng camera;
- theo dõi khuôn mặt/người dùng và gửi tốc độ hai bánh xuống ESP32 qua BLE;
- thực hiện cuộc gọi WebRTC với CaregiverApp;
- cung cấp chatbot giọng nói dùng Groq;
- phát lời nhắc lịch uống thuốc, sinh hoạt và sự kiện.

ESP32 chỉ điều khiển phần thân/bánh xe. CaregiverApp là giao diện từ xa cho người thân hoặc người chăm sóc.

## 2. Kiến trúc thực tế

~~~
CaregiverApp                         RobotApp
     |                                  |
     +--------- Firebase Auth ----------+
     +---------- Firebase RTDB ---------+
                 |       |
                 |       +-- dữ liệu, cấu hình, trạng thái
                 +---------- lệnh robot và signaling WebRTC

CaregiverApp <====== WebRTC audio/video ======> RobotApp
                         |
                   STUN/TURN bên ngoài

RobotApp -------- HTTPS --------> Groq API
RobotApp -------- BLE ----------> ESP32 --------> động cơ/bánh xe
~~~

Các kết luận quan trọng:

- Firebase Authentication và Firebase Realtime Database là hạ tầng cloud dùng chung.
- Realtime Database đồng thời lưu dữ liệu, truyền lệnh và làm kênh signaling cho WebRTC.
- Dự án không có MQTT broker.
- Dự án không có signaling server tự triển khai riêng.
- STUN/TURN chỉ hỗ trợ thiết lập/relay kết nối WebRTC, không phải backend dữ liệu.
- Chatbot hiện dùng Groq; không dùng Gemini.

## 3. Công nghệ và cấu trúc

- Android/Kotlin, XML View Binding, minSdk 23, targetSdk 36.
- Firebase Auth, Realtime Database, Storage và Firestore dependency; luồng chính hiện tập trung ở Auth/RTDB.
- CameraX + ML Kit Face Detection.
- WebRTC native Android.
- BLE GATT cho ESP32.
- Retrofit/OkHttp cho Groq.
- TensorFlow Lite có trong dependency và asset model phục vụ các chức năng AI cục bộ.

Package chính: com.example.datn_v1.

Các file quan trọng:

| Khu vực | File |
|---|---|
| Khởi động và điều hướng | app/src/main/java/com/example/datn_v1/MainActivity.kt |
| Theo dõi camera, chủ sở hữu BLE | tracking_fragment.kt |
| SORT, khóa ID0 và Re-ID | tracking/SortTracker.kt, tracking/KalmanBoxTracker.kt, tracking/ReIdModule.kt |
| LSTM và hậu kiểm té ngã | tracking/LstmFallDetector.kt, tracking/FallVerificationEngine.kt |
| Chuyển sự kiện/kết quả xác nhận | TrackingService.kt, ReminderEvent.kt |
| Nhận lệnh Firebase và gửi BLE | RobotControlManager.kt |
| Cuộc gọi WebRTC | video_call_fragment.kt, WebRTCClient.kt |
| Signaling Firebase | SignalingClient.kt |
| Nhận cuộc gọi nền | IncomingCallService.kt |
| Chatbot UI/voice | chatbot_fragment.kt |
| Gọi LLM | GroqChatService.kt |
| Nhắc lịch | ReminderService.kt, ReminderReceiver.kt |
| Hồ sơ người dùng | UserProfile.kt |

## 4. Luồng chạy chính

1. MainActivity khởi tạo Firebase, navigation và các dịch vụ cần thiết.
2. tracking_fragment xin quyền camera, micro và BLE/location tùy phiên bản Android.
3. Sau khi đủ quyền, fragment mở camera và khởi động đúng một RobotControlManager.
4. RobotControlManager lắng nghe lệnh tại Firebase RTDB.
5. Lệnh hợp lệ được đổi sang tốc độ bánh trái/phải rồi gửi qua BLE cho ESP32.
6. Khi chuyển sang cuộc gọi, WebRTC trở thành camera owner và chuyển cùng local
   frame sang TrackingFragment để YOLO/auto-follow tiếp tục chạy. Fragment video
   không tạo thêm kết nối BLE.

## 5. Điều khiển robot

### 5.1. Hợp đồng lệnh Firebase

CaregiverApp ghi lệnh vào:

~~~
robot_control/
  x: Number
  y: Number
  ts: Unix epoch milliseconds
~~~

Quy ước runtime đã được hiệu chỉnh theo robot thật:

- x nằm trong [-1, 1], là kênh tiến/lùi; joystick kéo lên tạo x âm;
- y nằm trong [-1, 1], là kênh hiệu chỉnh quay;
- joystick Caregiver kéo trái tạo y dương, kéo phải tạo y âm;
- auto-follow dùng hệ tọa độ camera riêng: TURN_LEFT hiện ghi y=-0,50 và
  TURN_RIGHT ghi y=+0,50;
- x = 0 và y = 0 là dừng.

Không đổi dấu y chỉ dựa trên tên LEFT/RIGHT. Joystick, ảnh camera và hai motor
có các hệ tọa độ khác nhau; chuỗi giá trị hiện tại đã chạy đúng trên robot thật.

RobotApp ép x/y về [-1, 1], bỏ giá trị NaN/vô hạn và không chạy lệnh thiếu hoặc cũ quá 2 giây. Timestamp tương lai quá 5 giây cũng bị từ chối. Khi lệnh không hợp lệ, app gửi dừng xuống ESP32.

### 5.2. Ánh xạ sang bánh xe

Target motor tối đa phía Android là 20. Kênh quay có hệ số 0,15 và deadzone:

~~~
translation = x
steering = y * 0.15
leftMotorTarget  = (steering + translation) * 20
rightMotorTarget = (steering - translation) * 20
~~~

Nếu |x| > 0,3 và |y| < 0,3 thì steering được đặt về 0 để robot đi thẳng ổn định.

Các điểm chuẩn bảo vệ đúng công thức cũ đã có unit test:

| Lệnh x,y | Target motor trái | Target motor phải | Ý nghĩa đã hiệu chỉnh |
|---|---:|---:|---|
| -1, 0 | -20 | 20 | Tiến |
| 1, 0 | 20 | -20 | Lùi |
| 0, 1 | 3 | 3 | Kênh quay dương |
| 0, -1 | -3 | -3 | Kênh quay âm |

Hai motor lắp đối xứng nên target motor trái/phải không dùng chung một quy ước
dấu vật lý. Không suy ra hướng robot chỉ từ việc hai số cùng hay trái dấu.

### 5.3. BLE tới ESP32

- Device name: OhmniRobot
- Service UUID: 4FAFC201-1FB5-459E-8FCC-C5C9C331914B
- Characteristic UUID: BEB5483E-36E1-4688-B7F5-EA07361B26A8
- Ghi bằng WRITE_NO_RESPONSE.
- Payload ASCII: L<float>R<float>\n
- Ví dụ lệnh tiến cực đại theo calibration hiện tại: L-20.00R20.00\n
- Dùng Locale.US để dấu thập phân luôn là dấu chấm.
- Android gửi lại lệnh trong khoảng tối đa 1200 ms; firmware tự dừng nếu hơn 800 ms không nhận lệnh.

tracking_fragment là chủ sở hữu duy nhất của RobotControlManager/BLE trong Activity. Không tạo thêm manager ở video_call_fragment vì hai GATT client sẽ tranh kết nối với cùng ESP32.

## 6. Camera và theo dõi

- Ngoài cuộc gọi, CameraX cung cấp preview và ImageAnalysis.
- Trong cuộc gọi, WebRTC capture camera một lần rồi chia sẻ local VideoFrame
  sang cùng pipeline YOLO; CameraX vẫn unbind để tránh tranh camera.
- ML Kit phát hiện khuôn mặt.
- Kết quả theo dõi được đổi thành cặp tốc độ bánh xe và gửi qua RobotControlManager/BLE.
- Cờ điều khiển/tính năng từ CaregiverApp được đồng bộ qua Firebase RTDB.
- Khi kết thúc gọi, WebRTC capturer phải được dispose trước khi CameraX bind lại.

Người cần theo dõi được chuẩn hóa thành ID0 và là người duy nhất được đưa vào
LSTM. Khi chưa có mục tiêu, người có bbox lớn nhất trong frame đầu tiên được
chọn. ID0 được bảo vệ lâu hơn track nhiễu (45 frame so với 10 frame), ghép bằng
nhiều dấu hiệu gồm IoU, khoảng cách tâm chuẩn hóa, điểm neo vai/hông, diện tích
bbox và histogram vùng thân. Histogram chỉ cập nhật định kỳ khi có đủ keypoint
để hạn chế học nhầm tư thế bị che khuất.

Nếu ID0 mất tạm thời, hệ thống chỉ gán lại ID0 khi trong khung hình có đúng một
người, người đó còn gần vị trí cuối (không quá 28% đường chéo frame), nằm trong
vùng trung tâm và ổn định 3 frame. Cửa sổ khôi phục là 8 giây; nếu có nhiều
người thì không tự đổi mục tiêu. Kalman cho phép cập nhật nhanh tỉ lệ bbox khi
người chuyển đứng/ngồi/nằm để giảm đổi ID do IoU tụt mạnh.

Phát hiện té ngã gồm hai tầng:

1. LSTM nhận cửa sổ 15 frame × 69 đặc trưng của ID0. Phải có 10 frame fall liên
   tiếp mới tạo tín hiệu; một frame normal sẽ reset bộ đếm liên tiếp.
2. Khi raw fall vừa ngừng, FallVerificationEngine hậu kiểm ngay, không chờ thêm
   15 frame để vào trạng thái Lying. Đầu/vai ổn định trong 30% phía trên frame
   được xem là nhiễu. Mất bbox 3 frame hoặc bbox bị cắt là bằng chứng mạnh. Với
   bbox đầy đủ, hệ thống chấm điểm theo vị trí thân trên thấp, số keypoint thấy
   được không quá 8, bbox nằm ngang và độ tụt thân trên so với baseline.

Khi hậu kiểm yêu cầu xác nhận, robot dừng và chỉ phát một FallEvent cho chatbot.
Một sự cố chỉ được hỏi/cảnh báo một lần cho tới khi người dùng phục hồi ổn định.

## 7. Chatbot

Luồng hiện tại:

~~~
Người dùng nói/nhập
  -> chatbot_fragment
  -> GroqChatService
  -> Groq Chat Completions API
  -> hiển thị và/hoặc Text-to-Speech
~~~

Groq là LLM runtime chính. GeminiChatService và Google Generative AI dependency cũ đã được loại bỏ vì không được sử dụng.

Giao diện chatbot tập trung vào icon lớn, tên/trạng thái và một dòng nội dung
đang nhận dạng hoặc đang được TTS phát; lịch sử chat, nút mic và nút xóa không
hiển thị ở màn hình chính. Thanh điều hướng nằm trong drawer ẩn ở mép trái.
Chat Completions được đọc theo streaming delta và ghép thành câu; TTS phát theo
đoạn câu hoàn chỉnh để giảm độ trễ nhưng tránh đọc từng token rời rạc. Sau khi
TTS kết thúc, state machine chuyển thẳng về listening thay vì dừng ở IDLE.

Với FallEvent đã hậu kiểm, chatbot là owner duy nhất tạo một record
`fall_alerts`, hỏi bằng TTS rồi nghe tối đa 10 giây. Phản hồi rõ ràng được phân
loại nhanh bằng từ khóa cục bộ; câu mơ hồ mới gọi Groq không-stream. Lỗi API,
phản hồi nguy hiểm hoặc không có hồi âm đều mặc định là nguy hiểm, cập nhật
cùng record và tự mở cuộc gọi video sau khi TTS kết thúc.

Lưu ý bảo mật: không commit API key thật vào source hoặc tài liệu. Nên lấy khóa từ local.properties/BuildConfig hoặc backend bảo mật và xoay khóa nếu từng bị lộ.

## 8. WebRTC

- Audio/video đi trực tiếp qua WebRTC hoặc qua TURN khi P2P không khả dụng.
- Firebase RTDB làm signaling: trạng thái cuộc gọi, offer, answer, ICE candidates
  và `emergencyReason="fall"` cho cuộc gọi phát sinh từ cảnh báo té ngã.
- IncomingCallService theo dõi lời mời gọi khi app ở nền.
- Không có server signaling riêng trong repository.
- Cần kiểm tra cấu hình STUN/TURN và vòng đời camera/micro khi sửa luồng gọi.
- Local VideoTrack có thêm một VideoSink cho TrackingFragment. Frame được
  downscale, đổi I420 sang RGB và đưa vào executor YOLO với cơ chế drop-frame;
  do đó video call, phát hiện té ngã và auto-follow có thể chạy đồng thời mà
  không mở camera lần thứ hai.

## 9. Firebase RTDB

Các nhánh chính phải kiểm tra trong code trước khi đổi schema:

- robot_control: x, y, ts;
- tracking/các cờ tính năng robot;
- webrtc_signal/cuộc gọi WebRTC, gồm emergencyReason khi gọi khẩn cấp;
- fall_alerts: một push record cho mỗi sự cố, gồm trạng thái xác nhận, mức độ và
  bằng chứng hậu kiểm (`verificationReason`, `visibleKeypoints`,
  `bboxFullyInside`, `upperBodyYRatio`);
- lịch nhắc, nhật ký và dữ liệu hồ sơ dùng chung.

Một số đường dẫn hiện mang tính toàn cục thay vì nằm dưới userId/robotId. Đây là rủi ro khi có nhiều người dùng hoặc nhiều robot; mọi thay đổi schema phải được áp dụng đồng bộ cho cả hai app và Firebase Security Rules.

## 10. Firmware ESP32

Repository firmware:

E:\LearnAndroidApp\Firmware_OhmniRobot\Code

Firmware:

- dùng ESP32 + SimpleFOC 2.2.2 và BLE;
- điều khiển hai BLDC theo velocity closed-loop;
- nhận lệnh L...R... từ RobotApp;
- giới hạn tốc độ firmware khoảng +/-40;
- tự dừng sau 800 ms không có lệnh;
- file .ino là nguồn triển khai hiện tại, file .txt là bản tham khảo cũ.

Comment ví dụ trong firmware chỉ mô tả dấu target ở mức motor. Hướng chuyển
động vật lý còn phụ thuộc chiều lắp motor/encoder; calibration đang chạy trong
RobotControlManager và kết quả thử robot thật là nguồn sự thật.

## 11. Vấn đề cần lưu ý

- Nhiều node Firebase còn dùng phạm vi toàn cục, chưa tách theo robotId/userId.
- Cần cấu hình và kiểm thử Firebase Security Rules cho dữ liệu điều khiển và signaling.
- Secret/API key không được hard-code hoặc commit.
- Cần kiểm thử thực tế chiều motor vì cách lắp cơ khí hoặc đảo dây có thể khác quy ước logic.
- Firmware parser nên được tăng cường kiểm tra dữ liệu lỗi trước khi gọi motor.move.
- Phiên bản thư viện và API ESP32 trong file .txt có thể khác file .ino.
- FallVerificationEngine đã có unit test cho các nhánh hậu kiểm chính; khôi phục
  ID0 và độ chính xác thực tế vẫn phải kiểm tra bằng video/tình huống đứng,
  ngồi, nằm và khuất người.

## 12. Kiểm tra sau khi thay đổi

Từ thư mục RobotApp:

~~~
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
~~~

Khi sửa contract Firebase, signaling hoặc command, phải kiểm tra đồng thời CaregiverApp, RobotApp và firmware.

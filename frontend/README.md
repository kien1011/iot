# Frontend IoT Control Center

Mở http://localhost:8081 sau khi chạy Backend, không dùng Live Server. Hướng dẫn tích hợp, API và kiểm thử ở ../README.md.

HTML, CSS, Chart.js, bảng, bộ lọc và tương tác được giữ từ ZIP gốc. Profile HTML và mọi CSS giữ nguyên từng byte. Các module JS lấy dữ liệu từ API cùng origin và WebSocket /ws, không sinh dữ liệu mẫu. `api.js` được `app.js` nạp dùng chung để Profile không cần sửa HTML.

Thời gian trong API là giờ Việt Nam; định dạng trực tiếp chuỗi DATETIME, không đổi sang múi giờ máy trình duyệt. Nhiệt độ/độ ẩm hiển thị một chữ số thập phân, Light hiển thị lux nguyên đã được Backend chuyển và lưu.

Đăng nhập nhận `{id, username, fullName, token}` và lưu vào `sessionStorage` (`iot.auth`). `api.js` gửi `Authorization: Bearer <token>` khi gọi API; logout thu hồi token ở backend rồi xóa dữ liệu xác thực trên frontend. Token hết hạn mặc định sau 30 phút. WebSocket dùng subprotocol `["bearer", token]`, không truyền token qua URL.

Dashboard gọi `/api/sensor-data/chart` không có query `limit`. Backend trả tối đa 20 điểm gần nhất cho mỗi cảm biến theo ID tăng dần; frontend gộp REST/WebSocket, loại trùng theo ID và tiếp tục giữ 20 điểm gần nhất như biểu đồ ban đầu.

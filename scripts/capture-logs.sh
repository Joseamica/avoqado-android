#!/usr/bin/env bash
# =============================================================================
# capture-logs.sh - Capture TPV + Server logs for Claude review
# =============================================================================
# Usage: ./scripts/capture-logs.sh <feature> [start|stop|read|status]
#
# Captures BOTH:
#   - Android TPV logs (adb logcat) - feature tags + ALL errors/crashes
#   - Server logs (avoqado-server) - feature tags + ALL errors
# =============================================================================

PACKAGE="com.jaac.avoqado_tpv.sandbox"
LOG_DIR="/tmp/avoqado-logs"
PID_FILE_TPV="$LOG_DIR/logcat_tpv.pid"
PID_FILE_SERVER="$LOG_DIR/logcat_server.pid"
SERVER_LOG="/Users/amieva/Documents/Programming/Avoqado/avoqado-server/logs/development.log"

mkdir -p "$LOG_DIR"

# =============================================================================
# ALWAYS CAPTURE THESE (errors, crashes, exceptions)
# =============================================================================
# Android: crashes, exceptions, fatal errors, ANR, network errors
ERROR_TAGS="AndroidRuntime|FATAL|Exception|Error|Crash|ANR|OkHttp|Retrofit|System.err|art|dalvikvm|DEBUG|ACRA"
# App-specific error patterns
APP_ERROR_TAGS="error|fail|crash|exception|timeout|refused|rejected|unauthorized|forbidden|not found|null|NPE"

# Server: errors, HTTP 4xx/5xx, unhandled exceptions
SERVER_ERROR_TAGS="error|Error|ERROR|fail|Fail|FAIL|exception|Exception|stack|Stack|warn|Warn|WARN|status.*[45][0-9][0-9]|statusCode.*[45][0-9][0-9]|unhandled|rejected|timeout|ECONNREFUSED|ENOTFOUND"

# =============================================================================
# FEATURE-SPECIFIC TAGS
# =============================================================================
get_tags() {
    local base_tags=""
    case "$1" in
        payment)   base_tags="PaymentViewModel|PaymentScreen|PaymentRepository|PaymentState|BlumonService|BlumonPay|EmvProcess|CardReader|MerchantAccount|PaymentSync|RefundViewModel|TransactionManager|CardPayment|PaymentMethod|PaymentResult|TipSelection|PaymentProcessor" ;;
        refund)    base_tags="PaymentViewModel|PaymentsViewModel|PaymentScreen|PaymentDetailBottomSheet|RefundConfirmationScreen|RecordRefundUseCase|RefundRecorder|RefundReason|RefundReceipt|CancelIcc|RefundAuthorization|Refund" ;;
        order)     base_tags="OrderViewModel|OrderRepository|OrderSync|OrderCache|QuickOrderViewModel|OrderSyncCoordinator|OrderSyncWorker|PendingPayment|CreateOrder|AddItems|OrderItem|OrderState|OrderService|CartViewModel" ;;
        menu)      base_tags="MenuViewModel|MenuRepository|ProductRepository|CategoryRepository|MenuCache|ProductCache|MenuService|ProductService|CategoryService|ModifierGroup|ProductVariant" ;;
        bluetooth) base_tags="BluetoothPayment|BlePayment|BluetoothService|BleServer|BleClient|BluetoothQueue|BluetoothAdapter|BluetoothGatt|BleCallback|BluetoothDevice|BluetoothManager|BLE" ;;
        socket)    base_tags="SocketManager|SocketEvent|SocketIO|WebSocket|RealTime|socket.io|Socket|SocketClient|SocketConnection|RoomManager|EventEmitter" ;;
        auth)      base_tags="AuthRepository|AuthInterceptor|LoginViewModel|SecureStorage|TokenRefresh|Session|AuthService|LoginScreen|PinLogin|StaffAuth|TokenManager|RefreshToken|AccessToken" ;;
        printer)   base_tags="PrinterManager|PrintReceipt|ReceiptPrinter|Printer|Receipt|PrintService|PrintJob|PrinterStatus|ThermalPrinter|ReceiptBuilder|PrinterConnection" ;;
        sync)      base_tags="SyncWorker|SyncCoordinator|SyncRepository|PaymentSync|OrderSync|Heartbeat|HeartbeatWorker|SyncManager|SyncService|BackgroundSync|WorkManager" ;;
        table)     base_tags="TableViewModel|FloorPlan|TableRepository|TableCache|TableStatus|FloorElement|TableService|FloorPlanViewModel|TableAssignment|WaiterSection" ;;
        kiosk)     base_tags="KioskViewModel|KioskPayment|KioskMode|Kiosk|KioskScreen|KioskService|SelfService|KioskOrder" ;;
        inventory) base_tags="InventoryViewModel|SerializedInventory|ProofOfSale|InventoryProduct|InventoryService|StockLevel|InventoryCheck|ProductStock" ;;
        all)       base_tags="avoqado|Avoqado|AVOQADO|ViewModel|Repository|Service|Manager|Worker" ;;
        *)         base_tags="$1" ;;
    esac
    # Combine feature tags + error tags
    echo "${base_tags}|${ERROR_TAGS}|${APP_ERROR_TAGS}"
}

# Server-side tags to filter (from Node.js logs)
get_server_tags() {
    local base_tags=""
    case "$1" in
        payment)   base_tags="payment|Payment|PAYMENT|blumon|Blumon|charge|Charge|refund|Refund|transaction|Transaction|merchant|Merchant|stripe|Stripe|card|Card|tip|Tip" ;;
        refund)    base_tags="refund|Refund|REFUND|CancelIcc|recordRefund|refundReason|refunds|/refunds|payments:refund|processorData|blumon|Blumon" ;;
        order)     base_tags="order|Order|ORDER|item|Item|cart|Cart|ticket|Ticket|bill|Bill|addItem|createOrder|updateOrder|deleteOrder|orderItem|OrderItem" ;;
        menu)      base_tags="product|Product|menu|Menu|category|Category|modifier|Modifier|inventory|Inventory|recipe|Recipe|pricing|Pricing" ;;
        bluetooth) base_tags="bluetooth|Bluetooth|ble|BLE|device|Device|terminal|Terminal" ;;
        socket)    base_tags="socket|Socket|emit|Emit|room|Room|connect|Connect|disconnect|Disconnect|broadcast|Broadcast|join|Join|leave|Leave" ;;
        auth)      base_tags="auth|Auth|login|Login|token|Token|session|Session|staff|Staff|pin|PIN|refresh|Refresh|verify|Verify|permission|Permission" ;;
        printer)   base_tags="print|Print|receipt|Receipt|fiscal|Fiscal|ticket|Ticket" ;;
        sync)      base_tags="sync|Sync|heartbeat|Heartbeat|pending|Pending|queue|Queue|worker|Worker" ;;
        table)     base_tags="table|Table|floor|Floor|seat|Seat|waiter|Waiter|section|Section|assignment|Assignment" ;;
        kiosk)     base_tags="kiosk|Kiosk|self-service|selfService|SelfService" ;;
        inventory) base_tags="inventory|Inventory|serial|Serial|proof|Proof|stock|Stock|deduct|Deduct" ;;
        all)       base_tags="." ;;
        *)         base_tags="$1" ;;
    esac
    # Combine feature tags + error tags
    echo "${base_tags}|${SERVER_ERROR_TAGS}"
}

get_desc() {
    case "$1" in
        payment)   echo "Payment: card, Blumon SDK, merchant routing, refunds, tips" ;;
        refund)    echo "Refund: refund flow, permissions, CancelIcc, refund recording" ;;
        order)     echo "Order: create, modify, sync, cache, items" ;;
        menu)      echo "Menu: products, categories, modifiers, cache" ;;
        bluetooth) echo "BLE: iOS payments, device pairing, queue" ;;
        socket)    echo "Socket.IO: real-time events, rooms, broadcasts" ;;
        auth)      echo "Auth: login, PIN, tokens, sessions, permissions" ;;
        printer)   echo "Printer: receipts, fiscal format, thermal" ;;
        sync)      echo "Sync: background workers, heartbeat, queue" ;;
        table)     echo "Tables: floor plans, status, assignments" ;;
        kiosk)     echo "Kiosk: self-service, auto-dismiss" ;;
        inventory) echo "Inventory: stock, proof-of-sale, deductions" ;;
        all)       echo "ALL components (verbose)" ;;
        *)         echo "Custom: $1" ;;
    esac
}

show_help() {
    echo "Usage: $0 <feature> [start|stop|read|status]"
    echo ""
    echo "Captures BOTH TPV (Android) + Server (Node.js) logs!"
    echo "Always includes: errors, crashes, exceptions, stack traces"
    echo ""
    echo "Features:"
    echo "  payment    - Card payments, Blumon, refunds, tips"
    echo "  refund     - Refund flow, permissions, CancelIcc, refund recording"
    echo "  order      - Orders, items, sync, cache"
    echo "  menu       - Products, categories, modifiers"
    echo "  bluetooth  - BLE payments, device pairing"
    echo "  socket     - Socket.IO events, real-time"
    echo "  auth       - Login, tokens, sessions"
    echo "  printer    - Receipt printing"
    echo "  sync       - Background sync, heartbeat"
    echo "  table      - Floor plans, table status"
    echo "  kiosk      - Self-service mode"
    echo "  inventory  - Stock, proof-of-sale"
    echo "  all        - Everything (verbose)"
    echo ""
    echo "Commands:"
    echo "  start   - Start capturing"
    echo "  stop    - Stop capturing"
    echo "  read    - Output logs for Claude"
    echo "  status  - Check if running"
    echo ""
    echo "Example:"
    echo "  $0 payment start    # Start capturing payment logs"
    echo "  # Test on device..."
    echo "  $0 payment stop     # Stop and see summary"
}

start_capture() {
    local feature="$1"
    local tpv_tags
    tpv_tags=$(get_tags "$feature")
    local server_tags
    server_tags=$(get_server_tags "$feature")
    local desc
    desc=$(get_desc "$feature")
    local log_file="$LOG_DIR/${feature}-$(date +%Y%m%d_%H%M%S).log"

    # Stop any existing
    stop_capture 2>/dev/null || true

    # Clear logcat
    adb logcat -c 2>/dev/null

    # Header
    {
        echo "# =============================================="
        echo "# AVOQADO COMBINED LOG CAPTURE (TPV + SERVER)"
        echo "# =============================================="
        echo "# Feature: $feature"
        echo "# Description: $desc"
        echo "# Started: $(date)"
        echo "# "
        echo "# ALWAYS CAPTURES:"
        echo "#   - Feature-specific logs"
        echo "#   - ALL errors, crashes, exceptions"
        echo "#   - Stack traces, ANR, network failures"
        echo "#   - HTTP 4xx/5xx responses"
        echo "# "
        echo "# Format:"
        echo "#   [TPV]    = Android app logs"
        echo "#   [SERVER] = Node.js backend logs"
        echo "#   [ERROR]  = Errors (highlighted)"
        echo "# =============================================="
        echo ""
    } > "$log_file"

    # === Start TPV (Android) capture ===
    local app_pid
    app_pid=$(adb shell pidof -s "$PACKAGE" 2>/dev/null || echo "")

    if [ -n "$app_pid" ]; then
        # Capture from app PID with feature tags + errors
        adb logcat --pid="$app_pid" -v time 2>/dev/null | \
            grep --line-buffered -iE "$tpv_tags" | \
            sed -u 's/^/[TPV]    /' >> "$log_file" &
    else
        # Capture by package name + feature tags + errors
        adb logcat -v time 2>/dev/null | \
            grep --line-buffered -iE "$PACKAGE|$tpv_tags" | \
            sed -u 's/^/[TPV]    /' >> "$log_file" &
    fi
    echo $! > "$PID_FILE_TPV"

    # === Start Server capture ===
    if [ -f "$SERVER_LOG" ]; then
        tail -n 0 -f "$SERVER_LOG" 2>/dev/null | \
            grep --line-buffered -iE "$server_tags" | \
            sed -u 's/^/[SERVER] /' >> "$log_file" &
        echo $! > "$PID_FILE_SERVER"
        echo "✅ Server log capture started"
    else
        echo "⚠️  Server log not found: $SERVER_LOG"
        echo "    (Server logs will not be captured)"
    fi

    echo "$log_file" > "$LOG_DIR/current_log"
    echo "$feature" > "$LOG_DIR/current_feature"

    echo ""
    echo "✅ Started capturing '$feature' logs"
    echo "📱 TPV (Android): feature + errors/crashes"
    echo "🖥️  Server (Node): feature + errors/4xx/5xx"
    echo "📁 Log file: $log_file"
    echo ""
    echo "👉 Test the feature on device"
    echo "👉 Then tell Claude: 'ya terminé'"
    echo ""
    echo "To watch live: tail -f $log_file"
}

stop_capture() {
    local stopped=0

    # Stop TPV capture
    if [ -f "$PID_FILE_TPV" ]; then
        local pid
        pid=$(cat "$PID_FILE_TPV")
        kill "$pid" 2>/dev/null || true
        rm -f "$PID_FILE_TPV"
        stopped=1
    fi

    # Stop Server capture
    if [ -f "$PID_FILE_SERVER" ]; then
        local pid
        pid=$(cat "$PID_FILE_SERVER")
        kill "$pid" 2>/dev/null || true
        rm -f "$PID_FILE_SERVER"
        stopped=1
    fi

    if [ $stopped -eq 1 ]; then
        if [ -f "$LOG_DIR/current_log" ]; then
            local log_file
            log_file=$(cat "$LOG_DIR/current_log")
            if [ -f "$log_file" ]; then
                local lines
                lines=$(wc -l < "$log_file" | tr -d ' ')

                # Count by source
                local tpv_lines
                tpv_lines=$(grep -c "^\[TPV\]" "$log_file" 2>/dev/null || echo "0")
                local server_lines
                server_lines=$(grep -c "^\[SERVER\]" "$log_file" 2>/dev/null || echo "0")

                # Count errors
                local error_count
                error_count=$(grep -ciE "error|exception|crash|fail|fatal" "$log_file" 2>/dev/null || echo "0")

                {
                    echo ""
                    echo "# =============================================="
                    echo "# Stopped: $(date)"
                    echo "# TPV lines: $tpv_lines"
                    echo "# Server lines: $server_lines"
                    echo "# Errors found: $error_count"
                    echo "# Total lines: $lines"
                    echo "# =============================================="
                } >> "$log_file"

                echo "⏹️  Stopped capture"
                echo "   📱 TPV: $tpv_lines lines"
                echo "   🖥️  Server: $server_lines lines"
                if [ "$error_count" -gt 0 ]; then
                    echo "   🔴 Errors: $error_count found!"
                else
                    echo "   ✅ Errors: none"
                fi
                echo "   📊 Total: $lines lines"
            fi
        fi
    else
        echo "No capture running."
    fi
}

read_logs() {
    if [ -f "$LOG_DIR/current_log" ]; then
        local log_file
        log_file=$(cat "$LOG_DIR/current_log")
        if [ -f "$log_file" ]; then
            cat "$log_file"
        else
            echo "Log file not found."
            exit 1
        fi
    else
        echo "No active capture. Start with: $0 <feature> start"
        exit 1
    fi
}

check_status() {
    local tpv_running=0
    local server_running=0

    if [ -f "$PID_FILE_TPV" ]; then
        local pid
        pid=$(cat "$PID_FILE_TPV")
        if kill -0 "$pid" 2>/dev/null; then
            tpv_running=1
        fi
    fi

    if [ -f "$PID_FILE_SERVER" ]; then
        local pid
        pid=$(cat "$PID_FILE_SERVER")
        if kill -0 "$pid" 2>/dev/null; then
            server_running=1
        fi
    fi

    if [ $tpv_running -eq 1 ] || [ $server_running -eq 1 ]; then
        local feature
        feature=$(cat "$LOG_DIR/current_feature" 2>/dev/null || echo "unknown")
        local log_file
        log_file=$(cat "$LOG_DIR/current_log" 2>/dev/null || echo "")

        echo "🟢 RUNNING: $feature"

        if [ -f "$log_file" ]; then
            local tpv_lines
            tpv_lines=$(grep -c "^\[TPV\]" "$log_file" 2>/dev/null || echo "0")
            local server_lines
            server_lines=$(grep -c "^\[SERVER\]" "$log_file" 2>/dev/null || echo "0")
            local error_count
            error_count=$(grep -ciE "error|exception|crash|fail|fatal" "$log_file" 2>/dev/null || echo "0")

            echo "   📱 TPV: $tpv_lines lines"
            echo "   🖥️  Server: $server_lines lines"
            if [ "$error_count" -gt 0 ]; then
                echo "   🔴 Errors: $error_count"
            fi
            echo "   📁 File: $log_file"
        fi
    else
        echo "🔴 Not running"
        rm -f "$PID_FILE_TPV" "$PID_FILE_SERVER"
    fi
}

# Main
if [ $# -lt 1 ]; then
    show_help
    exit 1
fi

FEATURE="$1"
ACTION="${2:-start}"

case "$ACTION" in
    start)  start_capture "$FEATURE" ;;
    stop)   stop_capture ;;
    read)   read_logs ;;
    status) check_status ;;
    *)      show_help ;;
esac

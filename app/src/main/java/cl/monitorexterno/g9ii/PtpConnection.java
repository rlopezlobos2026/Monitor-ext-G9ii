package cl.monitorexterno.g9ii;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

final class PtpConnection {
    static final int OK = 0x2001;
    static final int DEVICE_BUSY = 0x2019;
    private static final int HEADER_SIZE = 12;
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    private final UsbDeviceConnection usb;
    private final UsbEndpoint input;
    private final UsbEndpoint output;
    private final byte[] receiveBuffer = new byte[16384];
    private int cursor;
    private int available;
    private int nextTransaction;

    PtpConnection(UsbDeviceConnection usb, UsbEndpoint input, UsbEndpoint output) {
        this.usb = usb;
        this.input = input;
        this.output = output;
    }

    Reply command(int operation, int... arguments) throws IOException {
        int transaction = nextTransaction++;
        ByteBuffer command = ByteBuffer.allocate(HEADER_SIZE + arguments.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(command.capacity()).putShort((short) 1)
                .putShort((short) operation).putInt(transaction);
        for (int argument : arguments) {
            command.putInt(argument);
        }
        byte[] bytes = command.array();
        if (usb.bulkTransfer(output, bytes, bytes.length, 1500) != bytes.length) {
            throw new IOException("No se pudo enviar " + hex(operation));
        }

        byte[] data = null;
        for (int i = 0; i < 2; i++) {
            byte[] header = new byte[HEADER_SIZE];
            readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int payloadSize = fields.getInt() - HEADER_SIZE;
            int type = fields.getShort() & 0xffff;
            int code = fields.getShort() & 0xffff;
            int responseTransaction = fields.getInt();
            if (responseTransaction != transaction || payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                throw new IOException("Cabecera PTP inesperada para " + hex(operation));
            }
            if (type == 2 && code == operation && data == null) {
                data = new byte[payloadSize];
                readFully(data);
            } else if (type == 3 && payloadSize <= 20 && payloadSize % 4 == 0) {
                byte[] parameters = new byte[payloadSize];
                readFully(parameters);
                return new Reply(code, parameters, data);
            } else {
                throw new IOException("Respuesta PTP inesperada para " + hex(operation));
            }
        }
        throw new IOException("Sin confirmación PTP para " + hex(operation));
    }

    private void readFully(byte[] destination) throws IOException {
        int written = 0;
        while (written < destination.length) {
            if (cursor == available) {
                available = 0;
                cursor = 0;
                for (int attempt = 0; attempt < 3 && available == 0; attempt++) {
                    available = usb.bulkTransfer(input, receiveBuffer, receiveBuffer.length, 1500);
                }
                if (available <= 0) {
                    throw new IOException("Se agotó el tiempo de espera del USB. Revisa el cable y el modo PC(Tether).");
                }
            }
            int amount = Math.min(destination.length - written, available - cursor);
            System.arraycopy(receiveBuffer, cursor, destination, written, amount);
            cursor += amount;
            written += amount;
        }
    }

    static String hex(int value) {
        return String.format(Locale.ROOT, "0x%04X", value);
    }

    static final class Reply {
        final int code;
        final byte[] parameters;
        final byte[] data;

        Reply(int code, byte[] parameters, byte[] data) {
            this.code = code;
            this.parameters = parameters;
            this.data = data;
        }

        int panasonicCode() {
            if (code == OK && parameters.length >= 4) {
                int status = ByteBuffer.wrap(parameters).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (status >= 0x2000 && status < 0x3000) {
                    return status;
                }
            }
            return code;
        }
    }
}

/// Base error for anything in the device layer, so callers catch one type
/// regardless of which vendor SDK actually failed underneath.
class DeviceException implements Exception {
  final String message;
  final Object? cause;
  const DeviceException(this.message, {this.cause});

  @override
  String toString() => 'DeviceException: $message';
}

/// Failed to open/maintain the connection to a device.
class DeviceConnectionException extends DeviceException {
  const DeviceConnectionException(super.message, {super.cause});
}

/// The device is connected but cannot do the requested action
/// (e.g. asking a print-only device to locate a tag).
class UnsupportedCapabilityException extends DeviceException {
  const UnsupportedCapabilityException(super.message, {super.cause});
}

/// No backend is registered/available for the detected hardware.
class NoDeviceBackendException extends DeviceException {
  const NoDeviceBackendException(super.message, {super.cause});
}

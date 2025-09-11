# ThinQ Connect Integration for Hubitat

This is a new Hubitat integration for LG ThinQ devices using the official LG ThinQ Connect API with PAT (Personal Access Token) authentication. This integration replaces the older reverse-engineered approach with the official documented API. Official API documentation: https://smartsolution.developer.lge.com/en/apiManage/thinq_connect

## Features

- **Official API**: Uses LG's documented ThinQ Connect API instead of reverse-engineered endpoints
- **PAT Authentication**: Simple Personal Access Token authentication (no complex OAuth flows)
- **Real-time Updates**: MQTT support for instant device status updates
- **Multiple Device Types**: Supports washers, dryers, dishwashers, refrigerators, and ovens
- **Reliable**: Built on the same foundation as the Home Assistant integration

## Requirements

1. **PAT Token**: You need a Personal Access Token from LG ThinQ Connect portal
2. **MQTT Certificates**: Client certificate, private key, and CA certificate for MQTT connection
3. **Supported Devices**: LG appliances that support ThinQ Connect

## Installation

### 1. Install the Apps and Drivers

1. Copy `thinq_connect_core.groovy` to your Hubitat hub as an App
2. Copy the device drivers (`thinq_connect_washer.groovy`, `thinq_connect_dryer.groovy`, etc.) as Device Drivers

### 2. Get Your PAT Token

1. Visit the LG ThinQ Connect portal
2. Create a new application or use an existing one
3. Generate a Personal Access Token (PAT)
4. Note your country/region

### 3. Obtain MQTT Certificates

You need two files, which can be generated locally using `openssl` or online via a service like https://csrgenerator.com/

- **Private Key**: RSA private key in PEM format  
  Example:  
  ```bash
  openssl genpkey -outform PEM -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out priv.key
  ```
- **Client Certificate Request**: Corresponding CSR based on csrconfig.txt  
  Example:
	```bash
  openssl req -new -nodes -key priv.key -config csrconfig.txt -out cert.csr
  ```

These can be generated once and reused. The certificates are used for secure MQTT communication.

### 4. Configure the Integration

1. Go to Apps → Add User App → ThinQ Connect Integration
2. Follow the setup wizard:
   - Enter your PAT token
   - Select your country
   - Test the API connection
   - Provide MQTT certificates
   - Select devices to integrate

## Supported Devices

Currently supported device types:
- Washers (`DEVICE_WASHER`)
- Dryers (`DEVICE_DRYER`)
- Dishwashers (`DEVICE_DISH_WASHER`)
- WashTower units (`DEVICE_WASHTOWER_WASHER`, `DEVICE_WASHTOWER_DRYER`)
- Microwaves (`DEVICE_MICROWAVE_OVEN`)

## Unsupported Devices

Not currently implemented but can be added upon request:
- Refrigerators (`DEVICE_REFRIGERATOR`)
- Ovens (`DEVICE_OVEN`)

## Device Capabilities

### Washer
- **Attributes**: Current state, remaining time, total time, operation mode, cycle count, etc.
- **Commands**: Toggle, Power Off, Set delay start
- **Capabilities**: Switch, Sensor, Contact Sensor (for door), Refresh

### Dryer
- **Attributes**: Current state, remaining time, dry level, temperature level, etc.
- **Commands**: Toggle, Power Off, Set delay start
- **Capabilities**: Switch, Sensor, Refresh

## API Endpoints

The integration uses these official ThinQ Connect API endpoints:

- **Base URL**: `https://api-{region}.lgthinq.com`
- **Device List**: `GET /devices`
- **Device Profile**: `GET /devices/{deviceId}/profile`
- **Device Status**: `GET /devices/{deviceId}/state`
- **Device Control**: `POST /devices/{deviceId}/control`
- **MQTT Registration**: `POST /client`
- **Push Notifications**: `POST /push/{deviceId}/subscribe`

## Differences from Old Integration

| Feature | Old Integration | New Integration |
|---------|----------------|-----------------|
| Authentication | OAuth + Certificate Generation | PAT Token |
| API | Reverse-engineered private API | Official documented API |
| Reliability | Prone to breaking | Stable official API |
| Setup Complexity | High (OAuth, CSR generation) | Low (just PAT token) |
| MQTT Certificates | Auto-generated | User-provided (can be static) |

## Setup Process

### Step 1: Main Setup
- Select log level
- Review requirements

### Step 2: Authentication
- Enter PAT token
- Select country/region
- Test API connection

### Step 3: MQTT Configuration
- MQTT server automatically detected from API
- Enter client certificate request
- Enter private key
- Register MQTT client

### Step 4: Device Selection
- Review discovered devices
- Select devices to integrate
- Complete installation

## Device Data Structure

The integration processes data from the ThinQ Connect API in this format:

```json
{
  "runState": {
    "currentState": "RUNNING"
  },
  "operation": {
    "washerOperationMode": "START"
  },
  "timer": {
    "remainHour": 1,
    "remainMinute": 30,
    "totalHour": 2,
    "totalMinute": 0
  },
  "remoteControlEnable": {
    "remoteControlEnabled": true
  },
  "cycle": {
    "cycleCount": 5
  }
}
```

## Troubleshooting

### Connection Issues
1. Verify your PAT token is valid
2. Check that your country/region is correct
3. Ensure your devices are registered with LG ThinQ

### MQTT Issues
1. Verify CSR format (PEM)
2. Check that certificates are not expired
3. Ensure MQTT server URL is correct
4. The LG API throttles client‑certificate issuance. In practice, requesting no more than one certificate per minute is reliable.

### Device Not Found
1. Check that device type is supported
2. Verify device is online in LG ThinQ app
3. Check device logs for specific errors

### Common Error Messages

- **"Connection failed"**: Check PAT token and internet connection
- **"MQTT setup failed"**: Verify certificate format and content
- **"No devices found"**: Ensure devices are registered and supported
- **"API GET failed"**: Check PAT token validity and permissions

## Logging

Enable debug logging to troubleshoot issues:
1. Set Log Level to "debug" in the app settings
2. Check Hubitat logs for detailed information
3. Look for API response codes and MQTT connection status

## Advanced Configuration

### Custom MQTT Server
If you have your own MQTT broker, you can use it instead of LG's:
1. Set up your MQTT broker with TLS support
2. Generate appropriate certificates
3. Configure the MQTT server URL in the integration

### Multiple Regions
The integration automatically detects the correct API region based on your country:
- **AIC**: Americas (US, CA, etc.)
- **KIC**: Korea/Asia-Pacific (KR, JP, AU, etc.)
- **EIC**: Europe/Middle East/Africa (GB, DE, FR, etc.)

## Contributing

This integration is based on the Home Assistant LG ThinQ integration and the `pythinqconnect` SDK. Contributions are welcome for:
- Additional device types
- Enhanced error handling
- Additional device capabilities
- Bug fixes

## File Structure

```
apps/
└── thinq_connect_core.groovy          # Main parent app
drivers/
├── thinq_connect_washer.groovy        # Washer device driver
├── thinq_connect_dryer.groovy         # Dryer device driver
├── thinq_connect_dishwasher.groovy    # Dishwasher device driver
└── README.md                          # This documentation
```

## Version History

- **v1.0**: Initial release with PAT authentication
- Support for washers and dryers
- MQTT real-time updates
- Official API integration

## License

This integration follows the same licensing as the original Home Assistant integration.

## Support

For issues and questions:
1. Check the troubleshooting section
2. Enable debug logging
3. Review Hubitat logs
4. Check device compatibility

## Future Enhancements

Planned features for future releases:
- Additional device types (air conditioners, refrigerators)
- Enhanced error handling and recovery
- Device-specific advanced controls
- Improved MQTT certificate management

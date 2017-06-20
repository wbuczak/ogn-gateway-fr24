/**
 * Copyright (c) 2014 OGN, All Rights Reserved.
 */

package org.ogn.gateway.plugin.fr24;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.ogn.commons.beacon.AircraftBeacon;
import org.ogn.commons.beacon.AircraftDescriptor;
import org.ogn.commons.beacon.forwarder.OgnAircraftBeaconForwarder;
import org.ogn.commons.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR24 forwarder plug-in for OGN gateway
 * 
 * @author Seb, re-factoring: wbuczak
 */
public class FR24Forwarder implements OgnAircraftBeaconForwarder {

	Logger									LOG				= LoggerFactory.getLogger(FR24Forwarder.class);

	private static final String				VERSION			= "1.0.0";

	private static final String				FR24_SRV_NAME	= "wro.fr24.com";
	private static final int				FR24_SRV_PORT	= 15099;

	private DatagramSocket					socket;
	private SocketAddress					serverAddress;
	private DatagramPacket					datagram;

	private Map<String, AircraftDescriptor>	descriptors		= new HashMap<>();

	/**
	 * default constructor
	 */
	public FR24Forwarder() {

	}

	@Override
	public String getName() {
		return "FR24 forwarder";
	}

	@Override
	public String getVersion() {
		return VERSION;
	}

	@Override
	public String getDescription() {
		return "relays OGN aircraft beacons to FlightRadar24 system";
	}

	@Override
	public void init() {
		try {
			socket = new DatagramSocket();
			serverAddress = new InetSocketAddress(InetAddress.getByName(FR24_SRV_NAME), FR24_SRV_PORT);
		} catch (Exception e) {
			LOG.error("could not connect to FR24 server", e);
		}
	}

	@Override
	public void stop() {
		if (socket != null) {
			try {
				socket.close();
			} catch (Exception ex) {
				LOG.warn("exception caught while trying to close a socket", ex);
			}
		}
	}

	@Override
	public void onBeacon(AircraftBeacon beacon, Optional<AircraftDescriptor> descriptor) {
		boolean sendDescriptor = false;

		if (descriptor.isPresent()) {

			if (!descriptors.containsKey(descriptor.get())) {
				descriptors.put(descriptor.get().getRegNumber(), descriptor.get());
				sendDescriptor = true;
			} else {
				AircraftDescriptor prevDescr = descriptors.get(descriptor.get().getRegNumber());
				// send the descriptor update to FR24 ONLY if it has changed
				if (!prevDescr.equals(descriptor)) {
					descriptors.put(descriptor.get().getRegNumber(), descriptor.get());
					sendDescriptor = true;
				}
			}

		} // if

		// update descriptor ONLY if needed
		if (sendDescriptor) {
			sendToFr24(beacon, descriptor);
		} else {
			sendToFr24(beacon);
		}
	}

	/**
	 * @param beacon
	 * @param descriptor
	 */
	private void sendToFr24(AircraftBeacon beacon, Optional<AircraftDescriptor> descriptor) {
		// NOTE: OGN descriptors are (for now) not needed by FR24. FR24 uses its
		// own resolvers
		sendToFr24(beacon);
	}

	private static int swap(int value) {
		int b1 = (value >> 0) & 0xff;
		int b2 = (value >> 8) & 0xff;
		int b3 = (value >> 16) & 0xff;
		int b4 = (value >> 24) & 0xff;

		return b1 << 24 | b2 << 16 | b3 << 8 | b4 << 0;
	}

	private static short swap(short value) {
		int b1 = value & 0xff;
		int b2 = (value >> 8) & 0xff;

		return (short) (b1 << 8 | b2 << 0);
	}

	public void sendToFr24(AircraftBeacon beacon) {

		LOG.trace("sending beacon to FR24: {}", JsonUtils.toJson(beacon));

		ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
		DataOutputStream dos = new DataOutputStream(baos);

		try {

			// Add data
			dos.writeInt(swap((int) (Long.parseLong(beacon.getId(), 16) | (beacon.getAddressType().getCode() << 24)
					| (beacon.getAircraftType().getCode()) << 26)));// Extended Id

			dos.writeInt(swap((int) (System.currentTimeMillis() / 1000L))); // Unix
																			// current
																			// timestamp
			dos.writeInt(swap((int) (beacon.getLat() * 10000000))); // 1e-7 deg
																	// (south?)
			dos.writeInt(swap((int) (beacon.getLon() * 10000000))); // 1e-7 deg
																	// (west?)
			dos.writeShort(swap((short) beacon.getAlt())); // Altitude in meters
			dos.writeShort(swap((short) (beacon.getClimbRate() * 10))); // Climb
																		// rate
																		// (m/s)
																		// * 10
			dos.writeShort(swap((short) ((beacon.getGroundSpeed() * 10) / 3.6))); // Speed
																					// from
																					// km/h
																					// to
																					// m/s
																					// *
																					// 10
			dos.writeShort(swap((short) ((beacon.getTrack() * 360) / 65536))); // Heading:
																				// 65536/360deg
			dos.writeShort(swap((short) ((beacon.getTurnRate() * 360) / 65536))); // TurnRate:
																					// 65536/360deg
																					// -
																					// not
																					// really
																					// sure.
																					// Seems
																					// to
																					// depend
																					// on
																					// flarm???
			dos.writeByte(beacon.getAircraftType().getCode()); // acftType
			dos.writeByte(beacon.getErrorCount()); // RxErr
			dos.writeByte(0); // accHor
			dos.writeByte(0); // accVer
			dos.writeByte(0); // movMode
			dos.writeByte(0); // Flag

			byte[] data = baos.toByteArray();

			if (datagram == null)
				datagram = new DatagramPacket(data, data.length, serverAddress);
			else
				// reuse old object to reduce GC pressure
				datagram.setData(data);

			socket.send(datagram);

			LOG.debug("Data sent to FR24 ({} bytes)", +data.length);

		} catch (PortUnreachableException e) {
			LOG.error("FR24 is not not listening (PortUnreachable)", e);
		} catch (Exception e) {
			LOG.error("Could not send beacon to FR24", e);
		}
	}

}
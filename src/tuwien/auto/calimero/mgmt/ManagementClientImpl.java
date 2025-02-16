/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2019 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.mgmt;

import static java.lang.String.format;
import static java.nio.ByteBuffer.allocate;
import static java.util.stream.Collectors.toList;
import static tuwien.auto.calimero.DataUnitBuilder.createAPDU;
import static tuwien.auto.calimero.DataUnitBuilder.createLengthOptimizedAPDU;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DetachEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXInvalidResponseException;
import tuwien.auto.calimero.KNXRemoteException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.ReturnCode;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.internal.EventListeners;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;

/**
 * Implementation of management client.
 * <p>
 * Uses {@link TransportLayer} internally for communication. <br>
 * All management service methods invoked after a detach of the network link are allowed
 * to throw {@link IllegalStateException}.
 *
 * @author B. Malinowsky
 */
public class ManagementClientImpl implements ManagementClient
{
	private static final int ADC_READ = 0x0180;
	private static final int ADC_RESPONSE = 0x01C0;

	private static final int AUTHORIZE_READ = 0x03D1;
	private static final int AUTHORIZE_RESPONSE = 0x03D2;

	private static final int DOA_WRITE = 0x3E0;
	private static final int DOA_READ = 0x3E1;
	private static final int DOA_RESPONSE = 0x3E2;
	private static final int DOA_SELECTIVE_READ = 0x3E3;

	private static final int IND_ADDR_READ = 0x0100;
	private static final int IND_ADDR_RESPONSE = 0x0140;
	private static final int IND_ADDR_WRITE = 0xC0;

	private static final int IND_ADDR_SN_READ = 0x03DC;
	private static final int IND_ADDR_SN_RESPONSE = 0x03DD;
	private static final int IND_ADDR_SN_WRITE = 0x03DE;

	private static final int DEVICE_DESC_READ = 0x300;
	private static final int DEVICE_DESC_RESPONSE = 0x340;

	private static final int KEY_WRITE = 0x03D3;
	private static final int KEY_RESPONSE = 0x03D4;

	private static final int MEMORY_READ = 0x0200;
	private static final int MEMORY_RESPONSE = 0x0240;
	private static final int MEMORY_WRITE = 0x0280;

	private static final int PROPERTY_DESC_READ = 0x03D8;
	private static final int PROPERTY_DESC_RESPONSE = 0x03D9;

	private static final int PROPERTY_READ = 0x03D5;
	private static final int PROPERTY_RESPONSE = 0x03D6;
	private static final int PROPERTY_WRITE = 0x03D7;

	private static final int SystemNetworkParamRead = 0b0111001000;
	private static final int SystemNetworkParamResponse = 0b0111001001;
	private static final int SystemNetworkParamWrite = 0b0111001010;

	private static final int NetworkParamRead = 0b1111011010;
	private static final int NetworkParamResponse = 0b1111011011;
	static final int NetworkParamWrite = 0b1111100100;

	private static final int MemoryExtendedWrite = 0b0111111011;
	private static final int MemoryExtendedWriteResponse = 0b0111111100;
	private static final int MemoryExtendedRead = 0b0111111101;
	private static final int MemoryExtendedReadResponse = 0b0111111110;

	// serves as both req and res
	private static final int RESTART = 0x0380;

	private class TLListener implements TransportListener
	{
		TLListener()
		{}

		@Override
		public void broadcast(final FrameEvent e)
		{
			checkResponse(e);
		}

		@Override
		public void dataConnected(final FrameEvent e)
		{
			checkResponse(e);
		}

		@Override
		public void dataIndividual(final FrameEvent e)
		{
			checkResponse(e);
		}

		@Override
		public void disconnected(final Destination d)
		{}

		@Override
		public void group(final FrameEvent e)
		{}

		@Override
		public void detached(final DetachEvent e)
		{}

		@Override
		public void linkClosed(final CloseEvent e)
		{
			logger.info("attached link was closed");
		}

		private void checkResponse(final FrameEvent e)
		{
			if (svcResponse != 0) {
				final byte[] tpdu = e.getFrame().getPayload();
				if (DataUnitBuilder.getAPDUService(tpdu) == svcResponse)
					synchronized (indications) {
						indications.add(e);
						indications.notify();
					}
			}
			listeners.fire(c -> c.accept(e));
		}
	};


	private static final boolean extMemoryServices = true;

	private final TransportLayer tl;
	private final TLListener tlListener = new TLListener();
	private volatile Priority priority = Priority.LOW;
	private volatile int responseTimeout = 5000; // [ms]
	private final Deque<FrameEvent> indications = new ArrayDeque<>();
	private volatile int svcResponse;
	private volatile boolean detached;
	private final Logger logger;

	private final EventListeners<Consumer<FrameEvent>> listeners;

	/**
	 * Creates a new management client attached to the supplied KNX network link.
	 * <p>
	 * The log service used by this management client is named "MC " +
	 * <code>link.getName()</code>.
	 *
	 * @param link network link used for communication with a KNX network, the client does not take ownership
	 * @throws KNXLinkClosedException if the network link is closed
	 */
	public ManagementClientImpl(final KNXNetworkLink link) throws KNXLinkClosedException
	{
		this(link, new TransportLayerImpl(link));
	}

	protected ManagementClientImpl(final KNXNetworkLink link, final TransportLayer transportLayer)
	{
		tl = transportLayer;
		tl.addTransportListener(tlListener);
		logger = LogService.getLogger("calimero.mgmt.MC " + link.getName());
		listeners = new EventListeners<>(logger);
	}

	/**
	 * Internal API.
	 *
	 * @param onEvent consumer to receive notifications about frame events
	 */
	public final void addEventListener(final Consumer<FrameEvent> onEvent)
	{
		listeners.add(onEvent);
	}

	/**
	 * Internal API.
	 *
	 * @param onEvent consumer to receive notifications about frame events
	 */
	public final void removeEventListener(final Consumer<FrameEvent> onEvent)
	{
		listeners.remove(onEvent);
	}

	@Override
	public void setResponseTimeout(final int timeout)
	{
		if (timeout <= 0)
			throw new KNXIllegalArgumentException("timeout not > 0");
		responseTimeout = timeout * 1000;
	}

	@Override
	public int getResponseTimeout()
	{
		return responseTimeout / 1000;
	}

	@Override
	public void setPriority(final Priority p)
	{
		priority = p;
	}

	@Override
	public Priority getPriority()
	{
		return priority;
	}

	@Override
	public Destination createDestination(final IndividualAddress remote,
		final boolean connectionOriented)
	{
		return tl.createDestination(remote, connectionOriented);
	}

	@Override
	public Destination createDestination(final IndividualAddress remote,
		final boolean connectionOriented, final boolean keepAlive, final boolean verifyMode)
	{
		return tl.createDestination(remote, connectionOriented, keepAlive, verifyMode);
	}

	@Override
	public void writeAddress(final IndividualAddress newAddress) throws KNXTimeoutException,
		KNXLinkClosedException
	{
		tl.broadcast(false, Priority.SYSTEM,
				DataUnitBuilder.createAPDU(IND_ADDR_WRITE, newAddress.toByteArray()));
	}

	@Override
	public synchronized IndividualAddress[] readAddress(final boolean oneAddressOnly)
		throws KNXTimeoutException, KNXRemoteException, KNXLinkClosedException,
		InterruptedException
	{
		final List<IndividualAddress> l = new ArrayList<>();
		try {
			svcResponse = IND_ADDR_RESPONSE;
			tl.broadcast(false, Priority.SYSTEM,
					DataUnitBuilder.createLengthOptimizedAPDU(IND_ADDR_READ, null));
			long wait = responseTimeout;
			final long end = System.currentTimeMillis() + wait;
			while (wait > 0) {
				l.add(new IndividualAddress(waitForResponse(null, 0, 0, wait)));
				if (oneAddressOnly)
					break;
				wait = end - System.currentTimeMillis();
			}
		}
		catch (final KNXTimeoutException e) {
			if (l.isEmpty())
				throw e;
		}
		finally {
			svcResponse = 0;
		}
		return l.toArray(new IndividualAddress[l.size()]);
	}

	@Override
	public void writeAddress(final byte[] serialNo, final IndividualAddress newAddress)
		throws KNXTimeoutException, KNXLinkClosedException
	{
		if (serialNo.length != 6)
			throw new KNXIllegalArgumentException("length of serial number not 6 bytes");
		final byte[] asdu = new byte[12];
		for (int i = 0; i < 6; ++i)
			asdu[i] = serialNo[i];
		asdu[6] = (byte) (newAddress.getRawAddress() >>> 8);
		asdu[7] = (byte) newAddress.getRawAddress();
		tl.broadcast(false, Priority.SYSTEM, DataUnitBuilder.createAPDU(IND_ADDR_SN_WRITE, asdu));
	}

	@Override
	public synchronized IndividualAddress readAddress(final byte[] serialNo)
		throws KNXTimeoutException, KNXRemoteException, KNXLinkClosedException,
		InterruptedException
	{
		if (serialNo.length != 6)
			throw new KNXIllegalArgumentException("length of serial number not 6 bytes");
		try {
			svcResponse = IND_ADDR_SN_RESPONSE;
			tl.broadcast(false, Priority.SYSTEM,
					DataUnitBuilder.createAPDU(IND_ADDR_SN_READ, serialNo));
			return new IndividualAddress(waitForResponse(null, 10, 10, responseTimeout));
		}
		finally {
			svcResponse = 0;
		}
	}

	@Override
	public void writeDomainAddress(final byte[] domain) throws KNXTimeoutException,
		KNXLinkClosedException
	{
		if (domain.length != 2 && domain.length != 6)
			throw new KNXIllegalArgumentException("invalid length of domain address");
		tl.broadcast(true, priority, DataUnitBuilder.createAPDU(DOA_WRITE, domain));
	}

	@Override
	public synchronized List<byte[]> readDomainAddress(final boolean oneDomainOnly)
		throws KNXLinkClosedException, KNXInvalidResponseException, KNXTimeoutException,
		InterruptedException
	{
		// we allow 6 bytes ASDU for RF domains
		return makeDOAs(readBroadcast(priority, DataUnitBuilder.createLengthOptimizedAPDU(DOA_READ, null), DOA_RESPONSE,
				2, 6, oneDomainOnly));
	}

	@Override
	public synchronized void readDomainAddress(final BiConsumer<IndividualAddress, byte[]> response)
		throws KNXLinkClosedException, KNXInvalidResponseException, KNXTimeoutException, InterruptedException
	{
		// we allow 6 bytes ASDU for RF domains
		readBroadcast(priority, DataUnitBuilder.createLengthOptimizedAPDU(DOA_READ, null), DOA_RESPONSE, 2, 6, false,
				response);
	}

	@Override
	public List<byte[]> readDomainAddress(final byte[] domain, final IndividualAddress start,
		final int range) throws KNXInvalidResponseException, KNXLinkClosedException,
		KNXTimeoutException, InterruptedException
	{
		if (domain.length != 2)
			throw new KNXIllegalArgumentException("length of domain address not 2 bytes");
		if (range < 0 || range > 255)
			throw new KNXIllegalArgumentException("range out of range [0..255]");
		final byte[] addr = start.toByteArray();
		return makeDOAs(readBroadcast(priority,
				DataUnitBuilder.createAPDU(DOA_SELECTIVE_READ, new byte[] { domain[0], domain[1],
					addr[0], addr[1], (byte) range }), DOA_RESPONSE, 2, 2, false));
	}

	@Override
	public List<byte[]> readNetworkParameter(final IndividualAddress remote, final int objectType, final int pid,
		final byte... testInfo)
		throws KNXLinkClosedException, KNXTimeoutException, KNXInvalidResponseException, InterruptedException
	{
		synchronized (this) {
			try {
				svcResponse = NetworkParamResponse;
				sendNetworkParameter(NetworkParamRead, remote, objectType, pid, testInfo);

				final BiPredicate<IndividualAddress, byte[]> testResponse = (responder, apdu) -> {
					if (apdu.length < 5)
						return false;

					final int receivedIot = (apdu[2] & 0xff) << 8 | (apdu[3] & 0xff);
					final int receivedPid = apdu[4] & 0xff;
					if (apdu.length == 5) {
						final String s = receivedPid == 0xff ? receivedIot == 0xffff ? "object type" : "PID"
								: "response";
						logger.info("network parameter read response from {} for interface object type {} "
								+ "PID {}: unsupported {}", responder, objectType, pid, s);
						return false;
					}
					return receivedIot == objectType && receivedPid == pid;
				};

				final var waitTime = Duration.ofMillis(responseTimeout);
				final List<byte[]> responses = waitForResponses(3, 14, testResponse, waitTime, false);

				final int prefix = 2 + 3 + testInfo.length;
				return responses.stream().map(r -> Arrays.copyOfRange(r, prefix, r.length)).collect(toList());
			}
			finally {
				svcResponse = 0;
			}
		}
	}

	@Override
	public void writeNetworkParameter(final IndividualAddress remote, final int objectType, final int pid,
		final byte... value) throws KNXLinkClosedException, KNXTimeoutException
	{
		sendNetworkParameter(NetworkParamWrite, remote, objectType, pid, value);
	}

	private void sendNetworkParameter(final int apci, final IndividualAddress remote, final int objectType,
		final int pid, final byte[] value) throws KNXTimeoutException, KNXLinkClosedException
	{
		if (objectType < 0 || objectType > 0xffff || pid < 0 || pid > 0xff)
			throw new KNXIllegalArgumentException("IOT or PID argument out of range");
		final byte[] asdu = new byte[3 + value.length];
		asdu[0] = (byte) (objectType >> 8);
		asdu[1] = (byte) objectType;
		asdu[2] = (byte) pid;
		for (int i = 0; i < value.length; i++)
			asdu[3 + i] = value[i];

		final Priority p = Priority.SYSTEM;
		final byte[] tsdu = DataUnitBuilder.createAPDU(apci, asdu);
		if (remote != null)
			tl.sendData(remote, p, tsdu);
		else
			tl.broadcast(true, p, tsdu);
	}

	@Override
	public List<byte[]> readSystemNetworkParameter(final int objectType, final int pid, final int operand,
		final byte... additionalTestInfo) throws KNXException, InterruptedException {

		if (operand < 0 || operand > 0xfe)
			throw new KNXIllegalArgumentException("operand out of range");
		final byte[] testInfo = allocate(1 + additionalTestInfo.length).put((byte) operand)
				.put(additionalTestInfo).array();

		synchronized (this) {
			try {
				svcResponse = SystemNetworkParamResponse;
				sendSystemNetworkParameter(SystemNetworkParamRead, objectType, pid, testInfo);

				final BiPredicate<IndividualAddress, byte[]> testParamType = (responder, apdu) -> {
					if (apdu.length < 6)
						return false;
					final int receivedIot = (apdu[2] & 0xff) << 8 | (apdu[3] & 0xff);
					final int receivedPid = (apdu[4] & 0xff) << 4 | (apdu[5] & 0xf0) >> 4;
					if (apdu.length == 6) {
						final String s = receivedPid == 0xff ? receivedIot == 0xffff ? "object type" : "PID" : "response";
						logger.info("system network parameter read response from {} for interface object type {} "
								+ "PID {}: unsupported {}", responder, objectType, pid, s);
						return false;
					}
					final int receivedOperand = apdu[6] & 0xff;
					return receivedIot == objectType && receivedPid == pid && receivedOperand == operand;
				};

				final Duration waitTime = Duration.ofSeconds(operand == 1 ? 1
						: operand == 2 || operand == 3 ? additionalTestInfo[0] & 0xff : getResponseTimeout())
						.plusMillis(500); // allow some communication overhead (medium access & device delay times)

				final List<byte[]> responders = waitForResponses(4, 12, testParamType, waitTime, false);
				final int prefix = 2 + 4 + 1 + additionalTestInfo.length;
				return responders.stream().map(r -> Arrays.copyOfRange(r, prefix, r.length)).collect(toList());
			}
			finally {
				svcResponse = 0;
			}
		}
	}

	@Override
	public void writeSystemNetworkParameter(final int objectType, final int pid, final byte... value)
			throws KNXLinkClosedException, KNXTimeoutException {
		sendSystemNetworkParameter(SystemNetworkParamWrite, objectType, pid, value);
	}

	private void sendSystemNetworkParameter(final int apci, final int objectType, final int pid, final byte[] value)
		throws KNXTimeoutException, KNXLinkClosedException {
		if (objectType < 0 || objectType > 0xffff || pid < 0 || pid > 0xfff)
			throw new KNXIllegalArgumentException("IOT or PID argument out of range");

		final byte[] asdu = allocate(4 + value.length).putShort((short) objectType).putShort((short) (pid << 4))
				.put(value).array();

		final byte[] tsdu = DataUnitBuilder.createAPDU(apci, asdu);
		tl.broadcast(true, Priority.SYSTEM, tsdu);
	}

	@Override
	public byte[] readDeviceDesc(final Destination dst, final int descType)
		throws KNXInvalidResponseException, KNXDisconnectException, KNXTimeoutException,
		KNXLinkClosedException, InterruptedException
	{
		if (descType < 0 || descType > 63)
			throw new KNXIllegalArgumentException("descriptor type out of range [0..63]");
		final byte[] apdu = sendWait2(dst, priority, DataUnitBuilder.createLengthOptimizedAPDU(
				DEVICE_DESC_READ, new byte[] { (byte) descType }), DEVICE_DESC_RESPONSE, 2, 14);
		final byte[] dd = new byte[apdu.length - 2];
		for (int i = 0; i < apdu.length - 2; ++i)
			dd[i] = apdu[2 + i];
		return dd;
	}

	@Override
	public void restart(final Destination dst) throws KNXTimeoutException, KNXLinkClosedException, InterruptedException
	{
		try {
			restart(true, dst, 0, 0);
		}
		catch (final KNXRemoteException ignore) { }
		catch (final KNXDisconnectException ignore) { }
	}

	@Override
	public int restart(final Destination dst, final int eraseCode, final int channel)
		throws KNXTimeoutException, KNXRemoteException, KNXLinkClosedException,
		KNXDisconnectException, InterruptedException
	{
		return restart(false, dst, eraseCode, channel);
	}

	// for erase codes 1,3,4 the channel should be 0
	private int restart(final boolean basic, final Destination dst, final int eraseCode,
		final int channel) throws KNXTimeoutException, KNXRemoteException, KNXLinkClosedException,
		KNXDisconnectException, InterruptedException
	{
		int time = 0;
		if (basic) {
			send(dst, priority, DataUnitBuilder.createLengthOptimizedAPDU(RESTART, null), 0);
		}
		else {
			final byte[] sdu = new byte[] { 0x01, (byte) eraseCode, (byte) channel, };
			final byte[] send = DataUnitBuilder.createLengthOptimizedAPDU(RESTART, sdu);
			final byte[] apdu = sendWait2(dst, priority, send, RESTART, 3, 3);
			// check we get a restart response
			if ((apdu[1] & 0x32) == 0)
				throw new KNXInvalidResponseException("restart response bit not set");
			// defined error codes: 0,1,2,3
			final String[] codes = new String[] { "Success", "Access Denied",
				"Unsupported Erase Code", "Invalid Channel Number", "Unknown Error" };
			final int error = Math.min(apdu[2] & 0xff, 4);
			if (error > 0)
				throw new KNXRemoteException("master reset: " + codes[error]);
			time = ((apdu[3] & 0xff) << 8) | (apdu[4] & 0xff);
		}

		if (dst.isConnectionOriented()) {
			// a remote endpoint is allowed to not send a TL disconnect before restart, but
			// a TL disconnect timeout shall not be treated as protocol error
			final Object lock = new Object();
			final TransportListener l = new TLListener()
			{
				@Override
				public void disconnected(final Destination d)
				{
					if (d.equals(dst))
						synchronized (lock) {
							lock.notify();
						}
				};
			};
			tl.addTransportListener(l);
			try {
				synchronized (lock) {
					while (dst.getState() != Destination.State.Disconnected)
						lock.wait();
				}
			}
			finally {
				tl.removeTransportListener(l);
			}
			// always force a disconnect from our side
			tl.disconnect(dst);
		}
		return time;
	}

	@Override
	public byte[] readProperty(final Destination dst, final int objIndex, final int propertyId,
		final int start, final int elements) throws KNXTimeoutException, KNXRemoteException,
		KNXDisconnectException, KNXLinkClosedException, InterruptedException
	{
		final List<byte[]> l = readProperty(dst, objIndex, propertyId, start, elements, true);
		if (l.isEmpty())
			throw new KNXTimeoutException("timeout waiting for property response");
		return l.get(0);
	}

	// as readProperty, but collects all responses until response timeout is reached
	List<byte[]> readProperty2(final Destination dst, final int objIndex, final int propertyId,
		final int start, final int elements) throws KNXTimeoutException, KNXRemoteException,
		KNXDisconnectException, KNXLinkClosedException, InterruptedException
	{
		return readProperty(dst, objIndex, propertyId, start, elements, false);
	}

	private List<byte[]> readProperty(final Destination dst, final int objIndex, final int propertyId,
		final int start, final int elements, final boolean oneResponseOnly)
		throws KNXTimeoutException, KNXRemoteException, KNXDisconnectException,
		KNXLinkClosedException, InterruptedException
	{
		if (objIndex < 0 || objIndex > 255 || propertyId < 0 || propertyId > 255
			|| start < 0 || start > 0xFFF || elements < 0 || elements > 15)
			throw new KNXIllegalArgumentException(String.format("argument value out of range: "
					+ "OI 0 < %d < 256, PID 0 < %d < 256, start 0 < %d < 256, elems 0 < %d < 16",
					objIndex, propertyId, start, elements));
		final byte[] asdu = new byte[4];
		asdu[0] = (byte) objIndex;
		asdu[1] = (byte) propertyId;
		asdu[2] = (byte) ((elements << 4) | ((start >>> 8) & 0xF));
		asdu[3] = (byte) start;

		final List<byte[]> responses = new ArrayList<>();
		final List<KNXRemoteException> exceptions = new ArrayList<>();
		synchronized (this) {
			try {
				send(dst, priority, DataUnitBuilder.createAPDU(PROPERTY_READ, asdu), PROPERTY_RESPONSE);
				// if we are waiting for several responses, pass null for address to accept messages from any sender
				waitForResponses(oneResponseOnly ? dst.getAddress() : null, priority, 4, 14, apdu -> {
					try {
						responses.add(extractPropertyElements(apdu, objIndex, propertyId, elements));
						return oneResponseOnly;
					}
					catch (final KNXInvalidResponseException e) {
						logger.debug("skip invalid property read response: {}", e.getMessage());
						return false;
					}
					catch (final KNXRemoteException e) {
						exceptions.add(e);
						return oneResponseOnly;
					}
				});
			}
			finally {
				svcResponse = 0;
			}
		}
		if (responses.isEmpty()) {
			if (exceptions.size() == 1)
				throw exceptions.get(0);
			final KNXRemoteException e = new KNXRemoteException(
					"reading property " + dst.getAddress() + " OI " + objIndex + " PID " + propertyId + " failed");
			if (exceptions.size() > 0)
				exceptions.forEach(e::addSuppressed);
			throw e;
		}
		return responses;
	}

	@Override
	public void writeProperty(final Destination dst, final int objIndex,
		final int propertyId, final int start, final int elements, final byte[] data)
		throws KNXTimeoutException, KNXRemoteException, KNXDisconnectException,
		KNXLinkClosedException, InterruptedException
	{
		if (objIndex < 0 || objIndex > 255 || propertyId < 0 || propertyId > 255 || start < 0
				|| start > 0xFFF || data.length == 0 || elements < 0 || elements > 15)
			throw new KNXIllegalArgumentException("argument value out of range");
		final byte[] asdu = new byte[4 + data.length];
		asdu[0] = (byte) objIndex;
		asdu[1] = (byte) propertyId;
		asdu[2] = (byte) ((elements << 4) | ((start >>> 8) & 0xF));
		asdu[3] = (byte) start;
		for (int i = 0; i < data.length; ++i)
			asdu[4 + i] = data[i];
		final byte[] send = DataUnitBuilder.createAPDU(PROPERTY_WRITE, asdu);
		final byte[] apdu = sendWait2(dst, priority, send, PROPERTY_RESPONSE, 4, 14);
		// if number of elements is 0, remote app had problems
		final int elems = (apdu[4] & 0xFF) >> 4;
		if (elems == 0)
			throw new KNXRemoteException("property write failed/forbidden");
		if (elems != elements)
			throw new KNXInvalidResponseException("number of elements differ");
		if (data.length != apdu.length - 6)
			throw new KNXInvalidResponseException("data lengths differ, bytes: "
				+ data.length + " written, " + (apdu.length - 6) + " response");
		// explicitly read back written properties
		for (int i = 4; i < asdu.length; ++i)
			if (apdu[2 + i] != asdu[i])
				throw new KNXRemoteException("read back failed (erroneous property data)");
	}

	private static final int PropertyExtDescRead = 0b0111010010;
	private static final int PropertyExtDescResponse = 0b0111010011;

	private int[] getOrQueryInterfaceObjectList(final Destination dst)
			throws KNXTimeoutException, KNXDisconnectException, KNXLinkClosedException, InterruptedException {
		final Optional<int[]> opt = dst.interfaceObjectList();
		if (opt.isPresent())
			return opt.get();
		int[] list = {};
		try {
			final int elems = unsigned(readProperty(dst, 0, PID.IO_LIST, 0, 1));
			list = new int[elems];
			// NYI use bigger stride based on supported apdu length
			for (int i = 0; i < list.length; i++)
				list[i] = unsigned(readProperty(dst, 0, PID.IO_LIST, i + 1, 1));
		}
		catch (final KNXRemoteException e) {
			logger.debug("device {} does not support extended property services ({})", dst.getAddress(), e.toString());
		}
		dst.setInterfaceObjectList(list);
		return list;
	}

	private static int unsigned(final byte[] data) {
		int i = 0;
		for (final byte b : data)
			i = i << 8 | b & 0xff;
		return i;
	}

	private Description readPropertyExtDescription(final Destination dst, final int objIndex, final int propertyId,
		final int propIndex) throws KNXTimeoutException, KNXRemoteException, KNXDisconnectException,
		KNXLinkClosedException, InterruptedException {
		if (objIndex < 0 || objIndex > 255 || propertyId < 0 || propertyId > 255 || propIndex < 0 || propIndex > 255)
			throw new KNXIllegalArgumentException("argument value out of range");

		final var ioList = getOrQueryInterfaceObjectList(dst);
		if (!(ioList.length > objIndex))
			return null;

		final int objType = ioList[objIndex];
		final int objInstance = 1;
		final int propDescType = 0;
		final byte[] send = DataUnitBuilder.createAPDU(PropertyExtDescRead,
				new byte[] { (byte) (objType >> 8), (byte) objType, (byte) (objInstance >> 4),
					(byte) (((objInstance & 0xf) << 4) | (propertyId >> 8)), (byte) propertyId,
					(byte) ((propDescType << 4) | (propIndex >> 8)), (byte) (propertyId == 0 ? propIndex : 0) });

		for (int i = 0; i < 2; i++) {
			final byte[] apdu = sendWait2(dst, priority, send, PropertyExtDescResponse, 15, 15);
			final int rcvPropertyId = (((apdu[5] & 0xf) << 8) | (apdu[6] & 0xff));
			final int rcvPropDescType = (apdu[7] >> 4) & 0xf;
			final int rcvPropertyIdx = (((apdu[7] & 0xf) << 8) | (apdu[8] & 0xff));

			// make sure the response contains the requested description
			final boolean objTypeOk = objType == ((apdu[2] & 0xff) << 8 | apdu[3] & 0xff);
			final boolean oiOk = objInstance == ((apdu[4] & 0xff) << 4 | (apdu[5] & 0xf0) >> 4);
			final boolean pidOk = propertyId == 0 || propertyId == rcvPropertyId;
			final boolean pidxOk = propertyId != 0 || propIndex == rcvPropertyIdx;

			final int dptMain = (apdu[9] & 0xff) << 8 | apdu[10] & 0xff;
			final int dptSub = (apdu[11] & 0xff) << 8 | apdu[12] & 0xff;
			final boolean writeable = (apdu[13] & 0x80) == 0x80;
			final int pdt = apdu[13] & 0x2f;
			final int maxElems = (apdu[14] & 0xff) << 8 | apdu[15] & 0xff;
			final int readLevel = (apdu[16] & 0xf0) >> 4;
			final int writeLevel = apdu[16] & 0xf;
			if (rcvPropDescType == 0 && dptMain == 0 && dptSub == 0 && !writeable && pdt == 0 && maxElems == 0
					&& readLevel == 0 && writeLevel == 0) {
				throw new KNXRemoteException("problem with property description request (IOT or PID non-existant?)");
			}

			if (rcvPropDescType != 0)
				throw new KNXRemoteException("property description type " + rcvPropDescType + " not supported");

			if (objTypeOk && oiOk && pidOk && pidxOk)
				return Description.of(objIndex, Arrays.copyOfRange(apdu, 2, apdu.length));

			logger.warn("wrong description response: OI {} PID {} prop idx {}", apdu[2] & 0xff, apdu[3] & 0xff,
					apdu[4] & 0xff);
		}
		throw new KNXTimeoutException("timeout occurred while waiting for data response");
	}

	private static final boolean useExtPropertyServices = false;

	@Override
	public byte[] readPropertyDesc(final Destination dst, final int objIndex,
		final int propertyId, final int propIndex) throws KNXTimeoutException,
		KNXRemoteException, KNXDisconnectException, KNXLinkClosedException,
		InterruptedException
	{
		if (useExtPropertyServices) {
			final var desc = readPropertyExtDescription(dst, objIndex, propertyId, propIndex);
			if (desc != null)
				return desc.toByteArray();
		}

		if (objIndex < 0 || objIndex > 255 || propertyId < 0 || propertyId > 255 || propIndex < 0 || propIndex > 255)
			throw new KNXIllegalArgumentException("argument value out of range");
		final byte[] send = DataUnitBuilder.createAPDU(PROPERTY_DESC_READ, new byte[] {
			(byte) objIndex, (byte) propertyId, (byte) (propertyId == 0 ? propIndex : 0) });

		for (int i = 0; i < 2; i++) {
			final byte[] apdu = sendWait2(dst, priority, send, PROPERTY_DESC_RESPONSE, 7, 7);
			// make sure the response contains the requested description
			final boolean oiOk = objIndex == (apdu[2] & 0xff);
			final boolean pidOk = propertyId == 0 || propertyId == (apdu[3] & 0xff);
			final boolean pidxOk = propertyId != 0 || propIndex == (apdu[4] & 0xff);
			if (oiOk && pidOk && pidxOk) {
				// max_nr_elem field is a 4bit exponent + 12bit unsigned
				// on problem this field is 0
				if (apdu[6] == 0 && apdu[7] == 0)
					throw new KNXRemoteException("got no property description (object non-existant?)");
				return new byte[] { apdu[2], apdu[3], apdu[4], apdu[5], apdu[6], apdu[7], apdu[8] };
			}

			logger.warn("wrong description response: OI {} PID {} prop idx {}", apdu[2] & 0xff, apdu[3] & 0xff,
					apdu[4] & 0xff);
		}
		throw new KNXTimeoutException("timeout occurred while waiting for data response");
	}

	@Override
	public int readADC(final Destination dst, final int channel, final int repeat)
		throws KNXTimeoutException, KNXDisconnectException, KNXRemoteException,
		KNXLinkClosedException, InterruptedException
	{
		if (channel < 0 || channel > 63 || repeat < 0 || repeat > 255)
			throw new KNXIllegalArgumentException("ADC arguments out of range");
		if (dst.isConnectionOriented())
			tl.connect(dst);
		else
			logger.error("read ADC in connectionless mode, " + dst.toString());
		final byte[] apdu = sendWait(dst, priority,
				DataUnitBuilder.createLengthOptimizedAPDU(ADC_READ, new byte[] { (byte) channel,
					(byte) repeat }), ADC_RESPONSE, 3, 3);
		if (apdu[2] == 0)
			throw new KNXRemoteException("error reading value of A/D converter");
		return ((apdu[3] & 0xff) << 8) | apdu[4] & 0xff;
	}

	@Override
	public byte[] readMemory(final Destination dst, final int startAddr, final int bytes)
		throws KNXTimeoutException, KNXDisconnectException, KNXRemoteException,
		KNXLinkClosedException, InterruptedException
	{
		final int maxStartAddress = extMemoryServices ? 0xffffff : 0xffff;
		final int maxBytes = extMemoryServices ? 248 : 63;
		if (startAddr < 0 || startAddr > maxStartAddress || bytes < 1 || bytes > maxBytes)
			throw new KNXIllegalArgumentException("argument value out of range");
		if (dst.isConnectionOriented())
			tl.connect(dst);
		else
			logger.error("read memory in connectionless mode, " + dst.toString());

		// use extended read service for memory access above 65 K
		if (startAddr > 0xffff) {
			final byte[] send = createAPDU(MemoryExtendedRead,
					new byte[] { (byte) bytes, (byte) (startAddr >>> 16), (byte) (startAddr >>> 8), (byte) startAddr });
			final byte[] apdu = sendWait(dst, priority, send, MemoryExtendedReadResponse, 4, 252);
			final ReturnCode ret = ReturnCode.of(apdu[2] & 0xff);
			if (ret != ReturnCode.Success)
				throw new KNXRemoteException(
						format("read memory from %s 0x%x: %s", dst.getAddress(), startAddr, ret.description()));
			return Arrays.copyOfRange(apdu, 6, apdu.length);
		}

		final byte[] apdu = sendWait(dst, priority,
				createLengthOptimizedAPDU(MEMORY_READ,
						new byte[] { (byte) bytes, (byte) (startAddr >>> 8), (byte) startAddr }),
				MEMORY_RESPONSE, 2, 65);
		int no = apdu[1] & 0x3F;
		if (no == 0)
			throw new KNXRemoteException("could not read memory from 0x"
					+ Integer.toHexString(startAddr));
		final byte[] mem = new byte[no];
		while (--no >= 0)
			mem[no] = apdu[4 + no];
		return mem;
	}

	@Override
	public void writeMemory(final Destination dst, final int startAddr, final byte[] data)
		throws KNXDisconnectException, KNXTimeoutException, KNXRemoteException,
		KNXLinkClosedException, InterruptedException
	{
		final int maxStartAddress = extMemoryServices ? 0xffffff : 0xffff;
		final int maxBytes = extMemoryServices ? 250 : 63;
		if (startAddr < 0 || startAddr > maxStartAddress || data.length == 0 || data.length > maxBytes)
			throw new KNXIllegalArgumentException("argument value out of range");

		if (dst.isConnectionOriented())
			tl.connect(dst);
		else
			logger.error("write memory in connectionless mode, " + dst.toString());

		// use extended write service for memory access above 65 K
		if (startAddr > 0xffff) {
			final byte[] addrBytes = { (byte) (startAddr >>> 16), (byte) (startAddr >>> 8), (byte) startAddr };
			final byte[] asdu = allocate(4 + data.length).put((byte) data.length).put(addrBytes).put(data).array();
			final byte[] send = createAPDU(MemoryExtendedWrite, asdu);
			final byte[] apdu = sendWait(dst, priority, send, MemoryExtendedWriteResponse, 4, 252);
			final ReturnCode ret = ReturnCode.of(apdu[2] & 0xff);
			if (ret == ReturnCode.Success)
				return;
			String desc = ret.description();
			if (ret == ReturnCode.SuccessWithCrc) {
				final int crc = ((apdu[6] & 0xff) << 8) | (apdu[7] & 0xff);
				if (crc16Ccitt(asdu) == crc)
					return;
				desc = "data verification failed (crc mismatch)";
			}
			throw new KNXRemoteException(format("write memory to %s 0x%x: %s", dst.getAddress(), startAddr, desc));
		}

		final byte[] asdu = new byte[data.length + 3];
		asdu[0] = (byte) data.length;
		asdu[1] = (byte) (startAddr >> 8);
		asdu[2] = (byte) startAddr;
		for (int i = 0; i < data.length; ++i)
			asdu[3 + i] = data[i];
		final byte[] send = DataUnitBuilder.createLengthOptimizedAPDU(MEMORY_WRITE, asdu);
		if (dst.isVerifyMode()) {
			// explicitly read back data
			final byte[] apdu = sendWait(dst, priority, send, MEMORY_RESPONSE, 2, 65);
			if ((apdu[1] & 0x3f) == 0)
				throw new KNXRemoteException("remote app. could not write memory");
			if (apdu.length - 4 != data.length)
				throw new KNXInvalidResponseException("number of memory bytes differ");
			for (int i = 4; i < apdu.length; ++i)
				if (apdu[i] != asdu[i - 1])
					throw new KNXRemoteException("verify failed (erroneous memory data)");
		}
		else
			tl.sendData(dst, priority, send);
	}

	static int crc16Ccitt(final byte[] input) {
		final int polynom = 0x1021;
		final byte[] padded = Arrays.copyOf(input, input.length + 2);
		int result = 0xffff;
		for (int i = 0; i < 8 * padded.length; i++) {
			result <<= 1;
			final int nextBit = (padded[i / 8] >> (7 - (i % 8))) & 0x1;
			result |= nextBit;
			if ((result & 0x10000) != 0)
				result ^= polynom;
		}
		return result & 0xffff;
	}

	@Override
	public int authorize(final Destination dst, final byte[] key)
		throws KNXDisconnectException, KNXTimeoutException, KNXRemoteException,
		KNXLinkClosedException, InterruptedException
	{
		if (key.length != 4)
			throw new KNXIllegalArgumentException("length of authorize key not 4 bytes");
		if (dst.isConnectionOriented())
			tl.connect(dst);
		else
			logger.error("authorize in connectionless mode, " + dst.toString());
		final byte[] asdu = new byte[] { 0, key[0], key[1], key[2], key[3] };
		final byte[] apdu = sendWait(dst, priority,
			DataUnitBuilder.createAPDU(AUTHORIZE_READ, asdu), AUTHORIZE_RESPONSE, 1, 1);
		final int level = apdu[2] & 0xff;
		if (level > 15)
			throw new KNXInvalidResponseException("authorization level out of range [0..15]");
		return level;
	}

	@Override
	public void writeKey(final Destination dst, final int level, final byte[] key)
		throws KNXTimeoutException, KNXDisconnectException, KNXRemoteException,
		KNXLinkClosedException, InterruptedException
	{
		// level 255 is free access
		if (level < 0 || level > 254 || key.length != 4)
			throw new KNXIllegalArgumentException("level out of range or key length not 4 bytes");
		if (dst.isConnectionOriented())
			tl.connect(dst);
		else
			logger.error("write key in connectionless mode, " + dst.toString());
		final byte[] apdu = sendWait(dst, priority,
			DataUnitBuilder.createAPDU(KEY_WRITE, new byte[] { (byte) level, key[0],
				key[1], key[2], key[3] }), KEY_RESPONSE, 1, 1);
		if ((apdu[1] & 0xFF) == 0xFF)
			throw new KNXRemoteException("access denied: current access level > write level");
	}

	@Override
	public boolean isOpen()
	{
		return !detached;
	}

	@Override
	public KNXNetworkLink detach()
	{
		final KNXNetworkLink lnk = tl.detach();
		if (lnk != null) {
			logger.debug("detached from {}", lnk);
		}
		listeners.removeAll();
		detached = true;
		return lnk;
	}

	// helper which sets the expected svc response, and sends in CO or CL mode
	private void send(final Destination d, final Priority p, final byte[] apdu, final int response)
		throws KNXTimeoutException, KNXDisconnectException, KNXLinkClosedException
	{
		svcResponse = response;
		if (d.isConnectionOriented()) {
			tl.connect(d);
			tl.sendData(d, p, apdu);
		}
		else
			tl.sendData(d.getAddress(), p, apdu);
	}

	private synchronized byte[] sendWait(final Destination d, final Priority p,
		final byte[] apdu, final int response, final int minAsduLen, final int maxAsduLen)
		throws KNXDisconnectException, KNXTimeoutException, KNXInvalidResponseException,
		KNXLinkClosedException, InterruptedException
	{
		try {
			svcResponse = response;
			tl.sendData(d, p, apdu);
			return waitForResponse(d.getAddress(), minAsduLen, maxAsduLen, responseTimeout);
		}
		finally {
			svcResponse = 0;
		}
	}

	private synchronized byte[] sendWait2(final Destination d, final Priority p,
		final byte[] apdu, final int response, final int minAsduLen, final int maxAsduLen)
		throws KNXDisconnectException, KNXTimeoutException, KNXInvalidResponseException,
		KNXLinkClosedException, InterruptedException
	{
		try {
			send(d, p, apdu, response);
			return waitForResponse(d.getAddress(), minAsduLen, maxAsduLen, responseTimeout);
		}
		finally {
			svcResponse = 0;
		}
	}

	// timeout in milliseconds
	// min + max ASDU len are *not* including any field that contains ACPI
	private byte[] waitForResponse(final IndividualAddress from, final int minAsduLen, final int maxAsduLen,
		final long timeout) throws KNXInvalidResponseException, KNXTimeoutException, InterruptedException
	{
		return waitForResponse(from, minAsduLen, maxAsduLen, timeout, Optional.empty());
	}

	// timeout in milliseconds
	// min + max ASDU len are *not* including any field that contains ACPI
	private byte[] waitForResponse(final IndividualAddress from, final int minAsduLen, final int maxAsduLen,
		final long timeout, final Optional<List<IndividualAddress>> responders)
			throws KNXInvalidResponseException, KNXTimeoutException, InterruptedException
	{
		long remaining = timeout;
		final long end = System.currentTimeMillis() + remaining;
		synchronized (indications) {
			while (remaining > 0) {
				while (indications.size() > 0) {
					final CEMI frame = indications.remove().getFrame();
					final byte[] apdu = frame.getPayload();
					if (svcResponse != DataUnitBuilder.getAPDUService(apdu))
						continue;
					// broadcasts set parameter from to null; we then accept every sender address
					if (from != null) {
						if (!((CEMILData) frame).getSource().equals(from))
							continue;
					}
					if (apdu.length < minAsduLen + 2 || apdu.length > maxAsduLen + 2) {
						final String s = "invalid ASDU response length " + (apdu.length - 2)
								+ " bytes, expected " + minAsduLen + " to " + maxAsduLen;
						logger.error("received response with " + s);
						throw new KNXInvalidResponseException(s);
					}
					if (svcResponse == IND_ADDR_RESPONSE || svcResponse == IND_ADDR_SN_RESPONSE)
						return ((CEMILData) frame).getSource().toByteArray();
					indications.clear();
					responders.ifPresent((l) -> l.add(((CEMILData) frame).getSource()));
					return apdu;
				}
				indications.wait(remaining);
				remaining = end - System.currentTimeMillis();
			}
		}
		throw new KNXTimeoutException("timeout occurred while waiting for data response");
	}

	private List<byte[]> waitForResponses(final IndividualAddress from, final Priority p,
		final int minAsduLen, final int maxAsduLen, final boolean oneOnly)
		throws KNXInvalidResponseException, InterruptedException
	{
		final List<byte[]> l = new ArrayList<>();
		try {
			long wait = responseTimeout;
			final long end = System.currentTimeMillis() + wait;
			while (wait > 0) {
				l.add(waitForResponse(from, minAsduLen, maxAsduLen, wait));
				if (oneOnly)
					break;
				wait = end - System.currentTimeMillis();
			}
		}
		catch (final KNXTimeoutException e) {}
		return l;
	}

	private List<byte[]> waitForResponses(final int minAsduLen, final int maxAsduLen,
		final BiPredicate<IndividualAddress, byte[]> test, final Duration waitTime, final boolean oneOnly)
			throws KNXInvalidResponseException, InterruptedException {
		final List<byte[]> l = new ArrayList<>();
		try {
			long remaining = waitTime.toMillis();
			final long end = System.nanoTime() / 1_000_000 + remaining;
			while (remaining > 0) {
				final List<IndividualAddress> responder = new ArrayList<>();
				final byte[] res = waitForResponse(null, minAsduLen, maxAsduLen, remaining, Optional.of(responder));
				if (test.test(responder.get(0), res)) {
					l.add(res);
					if (oneOnly)
						break;
				}
				remaining = end - System.nanoTime() / 1_000_000;
			}
		}
		catch (final KNXTimeoutException e) {}
		return l;
	}

	private void waitForResponses(final IndividualAddress from, final Priority p, final int minAsduLen,
		final int maxAsduLen, final Predicate<byte[]> response)
		throws KNXInvalidResponseException, InterruptedException, KNXTimeoutException
	{
		long wait = responseTimeout * 1_000_000L;
		final long end = System.nanoTime() + wait;
		while (wait > 0) {
			if (response.test(waitForResponse(from, minAsduLen, maxAsduLen, wait / 1_000_000)))
				break;
			wait = end - System.nanoTime();
		}
	}

	private void waitForResponses(final IndividualAddress from, final Priority p, final int minAsduLen,
		final int maxAsduLen, final boolean oneOnly, final BiConsumer<IndividualAddress, byte[]> callback)
			throws KNXInvalidResponseException, InterruptedException
	{
		try {
			long wait = responseTimeout;
			final long end = System.currentTimeMillis() + wait;
			while (wait > 0) {
				final Optional<List<IndividualAddress>> src = Optional.of(new ArrayList<>());
				final byte[] response = waitForResponse(from, minAsduLen, maxAsduLen, wait, src);
				callback.accept(src.get().get(0), Arrays.copyOfRange(response, 2, response.length));
				if (oneOnly)
					break;
				wait = end - System.currentTimeMillis();
			}
		}
		catch (final KNXTimeoutException e) {}
	}

	private synchronized List<byte[]> readBroadcast(final Priority p, final byte[] apdu,
		final int response, final int minAsduLen, final int maxAsduLen, final boolean oneOnly)
		throws KNXLinkClosedException, KNXInvalidResponseException, KNXTimeoutException,
		InterruptedException
	{
		try {
			svcResponse = response;
			tl.broadcast(true, p, apdu);
			final List<byte[]> l = waitForResponses(null, p, minAsduLen, maxAsduLen, oneOnly);
			if (l.isEmpty())
				throw new KNXTimeoutException("timeout waiting for responses");
			return l;
		}
		finally {
			svcResponse = 0;
		}
	}

	private synchronized void readBroadcast(final Priority p, final byte[] apdu, final int response,
		final int minAsduLen, final int maxAsduLen, final boolean oneOnly,
		final BiConsumer<IndividualAddress, byte[]> callback)
			throws KNXLinkClosedException, KNXInvalidResponseException, KNXTimeoutException, InterruptedException
	{
		try {
			svcResponse = response;
			tl.broadcast(true, p, apdu);
			waitForResponses(null, p, minAsduLen, maxAsduLen, oneOnly, callback);
		}
		finally {
			svcResponse = 0;
		}
	}

	// cut domain addresses out of APDUs
	private static List<byte[]> makeDOAs(final List<byte[]> l)
	{
		for (int i = 0; i < l.size(); ++i) {
			final byte[] pdu = l.get(i);
			l.set(i, Arrays.copyOfRange(pdu, 2, pdu.length));
		}
		return l;
	}

	// returns property read.res element values
	private static byte[] extractPropertyElements(final byte[] apdu, final int objIndex, final int propertyId,
		final int elements) throws KNXRemoteException
	{
		final int oi = apdu[2] & 0xff;
		final int pid = apdu[3] & 0xff;
		if (oi != objIndex || pid != propertyId)
			throw new KNXInvalidResponseException(
					String.format("property response mismatch, expected OI %d PID %d (received %d|%d)", objIndex,
							propertyId, oi, pid));
		// check if number of elements is 0, indicates access problem
		final int number = (apdu[4] & 0xFF) >>> 4;
		if (number == 0)
			throw new KNXRemoteException("property access OI " + oi + " PID " + pid + " failed/forbidden");
		if (number != elements)
			throw new KNXInvalidResponseException(String.format(
					"property access OI %d PID %d expected %d elements (received %d)", oi, pid, elements, number));
		final byte[] prop = new byte[apdu.length - 6];
		for (int i = 0; i < prop.length; ++i)
			prop[i] = apdu[i + 6];
		return prop;
	}
}

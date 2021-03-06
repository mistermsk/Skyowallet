package fr.skyost.skyowallet.sync.synchronizer;

import fr.skyost.skyowallet.economy.EconomyObject;
import fr.skyost.skyowallet.economy.SkyowalletFactory;
import fr.skyost.skyowallet.economy.SkyowalletManager;
import fr.skyost.skyowallet.sync.SyncManager;
import fr.skyost.skyowallet.sync.connection.DatabaseConnection;
import fr.skyost.skyowallet.sync.connection.MySQLConnection;
import fr.skyost.skyowallet.sync.connection.SQLiteConnection;
import fr.skyost.skyowallet.sync.handler.SkyowalletResultSetHandler;
import fr.skyost.skyowallet.sync.queue.SyncQueue;
import fr.skyost.skyowallet.util.Util;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents a synchronizer that allows to synchronize a certain type of objects.
 *
 * @param <T> The object type.
 */

public abstract class SkyowalletSynchronizer<T extends EconomyObject> {

	/**
	 * The result set handler.
	 */

	private SkyowalletResultSetHandler<T> resultSetHandler;

	/**
	 * The manager.
	 */

	private SkyowalletManager<T> manager;

	/**
	 * The factory.
	 */

	private SkyowalletFactory<T, ?> factory;

	/**
	 * Creates a new Skyowallet synchronizer instance.
	 *
	 * @param resultSetHandler The result set handler.
	 * @param manager The manager.
	 * @param factory The factory.
	 */

	public SkyowalletSynchronizer(final SkyowalletResultSetHandler<T> resultSetHandler, final SkyowalletManager<T> manager, final SkyowalletFactory<T, ?> factory) {
		this.resultSetHandler = resultSetHandler;
		this.manager = manager;
		this.factory = factory;
	}

	/**
	 * Synchronizes the specified queue object.
	 *
	 * @param syncManager The synchronization manager.
	 * @param queue The queue of objects.
	 */

	public void synchronizeQueue(final SyncManager syncManager, final HashMap<String, T> queue) {
		final MySQLConnection mySQLConnection = Util.tryOpenConnection(syncManager.getMySQLConnection());
		final SQLiteConnection sqLiteConnection = Util.tryOpenConnection(syncManager.getSQLiteConnection());

		Util.ifNotNull(sqLiteConnection, connection -> syncObjectsWithDatabase(connection, queue));
		if(Util.ifNotNull(mySQLConnection, boolean.class, DatabaseConnection::isEnabled, connection -> false)) {
			Util.ifNotNull(mySQLConnection, connection -> syncObjectsWithDatabase(connection, queue));
			Util.ifNotNull(sqLiteConnection, connection -> syncObjectsWithDatabase(connection, queue));
			Util.ifNotNull(mySQLConnection, connection -> deleteRemovedObjects(connection, queue));
		}
		else {
			Util.ifNotNull(sqLiteConnection, connection -> syncObjectsWithDatabase(connection, queue));
		}
		Util.ifNotNull(sqLiteConnection, connection -> deleteRemovedObjects(connection, queue));

		Util.ifNotNull(mySQLConnection, DatabaseConnection::close);
		Util.ifNotNull(sqLiteConnection, DatabaseConnection::close);
	}

	/**
	 * Loads all new objects from a database.
	 *
	 * @param syncQueue The synchronization queue.
	 * @param connection The database connection.
	 * @param selectQuery The query that allows to select the objects.
	 *
	 * @throws SQLException If any SQL exception occurs.
	 */

	public void loadNewObjectsFromDatabase(final SyncQueue syncQueue, final DatabaseConnection connection, final String selectQuery) throws SQLException {
		final Set<T> objects = connection.executeQuery(selectQuery, resultSetHandler);
		for(final T mySQLObject : objects) {
			if(manager.has(mySQLObject.getIdentifier())) {
				continue;
			}
			manager.add(mySQLObject);
			syncQueue.enqueue(mySQLObject);
		}
	}

	/**
	 * Synchronizes objects with a database.
	 *
	 * @param connection The database connection.
	 * @param queue The queue of objects.
	 *
	 * @throws SQLException If any SQL exception occurs.
	 */

	protected void syncObjectsWithDatabase(final DatabaseConnection connection, final HashMap<String, T> queue) throws SQLException {
		final HashMap<String, T> queueCopy = new HashMap<>(queue);

		final HashSet<String> whereClause = new HashSet<>();
		for(final T object : queue.values()) {
			whereClause.add(handleIdentifier(connection, object));
		}

		if(whereClause.isEmpty()) {
			return;
		}

		final Set<T> objects = connection.executeQuery(buildSelectQuery(connection, whereClause), resultSetHandler);
		for(final T mySQLObject : objects) {
			final T memoryObject = queue.get(mySQLObject.getIdentifier());
			queueCopy.remove(memoryObject.getIdentifier());
			if(memoryObject.getLastModificationTime() == mySQLObject.getLastModificationTime()) {
				continue;
			}

			if(memoryObject.getLastModificationTime() < mySQLObject.getLastModificationTime()) {
				memoryObject.applyFromObject(mySQLObject);
			}
			else {
				final DatabaseQuery update = buildUpdateQuery(connection, memoryObject);
				connection.executeUpdate(update.query, update.arguments);
			}
		}

		for(final T object : queueCopy.values()) {
			final DatabaseQuery update = buildUpdateQuery(connection, object);
			connection.executeUpdate(update.query, update.arguments);
		}
	}

	/**
	 * Deletes the removed objects.
	 *
	 * @param connection The database connection.
	 * @param queue The queue of objects.
	 *
	 * @throws SQLException If any SQL exception occurs.
	 */

	protected void deleteRemovedObjects(final DatabaseConnection connection, final HashMap<String, T> queue) throws SQLException {
		final HashSet<String> whereClause = new HashSet<>();
		for(final Map.Entry<String, T> entry : new HashSet<>(queue.entrySet())) {
			final T object = entry.getValue();
			if(!object.isDeleted()) {
				continue;
			}

			whereClause.add(handleIdentifier(connection, object));
			manager.getData().remove(object.getIdentifier());
			queue.remove(entry.getKey());
		}

		if(whereClause.isEmpty()) {
			return;
		}

		final DatabaseQuery delete = buildDeleteQuery(connection, whereClause);
		connection.executeUpdate(delete.query, delete.arguments);
	}

	/**
	 * Handles an identifier.
	 *
	 * @param connection The database connection.
	 * @param object The current object.
	 *
	 * @return The handled identifier.
	 */

	public abstract String handleIdentifier(final DatabaseConnection connection, final T object);

	/**
	 * Builds a SELECT query.
	 *
	 * @param connection The database connection.
	 * @param whereClause The WHERE clause.
	 *
	 * @return The SELECT query.
	 */

	public abstract String buildSelectQuery(final DatabaseConnection connection, final Set<String> whereClause);

	/**
	 * Builds a UPDATE query.
	 *
	 * @param connection The database connection.
	 * @param object The current object.
	 *
	 * @return The UPDATE query.
	 */

	public abstract DatabaseQuery buildUpdateQuery(final DatabaseConnection connection, final T object);

	/**
	 * Builds a DELETE query.
	 *
	 * @param connection The database connection.
	 * @param whereClause The WHERE clause.
	 *
	 * @return The DELETE query.
	 */

	public abstract DatabaseQuery buildDeleteQuery(final DatabaseConnection connection, final Set<String> whereClause);

	/**
	 * Returns the result set handler.
	 *
	 * @return The result set handler.
	 */

	public SkyowalletResultSetHandler<T> getResultSetHandler() {
		return resultSetHandler;
	}

	/**
	 * Sets the result set handler.
	 *
	 * @param resultSetHandler The result set handler.
	 */

	public void setResultSetHandler(final SkyowalletResultSetHandler<T> resultSetHandler) {
		this.resultSetHandler = resultSetHandler;
	}

	/**
	 * Returns the manager.
	 *
	 * @return The manager.
	 */

	public SkyowalletManager<T> getManager() {
		return manager;
	}

	/**
	 * Sets the manager.
	 *
	 * @param manager The manager.
	 */

	public void setManager(final SkyowalletManager<T> manager) {
		this.manager = manager;
	}

	/**
	 * Returns the factory.
	 *
	 * @return The factory.
	 */

	public SkyowalletFactory<T, ?> getFactory() {
		return factory;
	}

	/**
	 * Sets the factory.
	 *
	 * @param factory The factory.
	 */

	public void setFactory(final SkyowalletFactory<T, ?> factory) {
		this.factory = factory;
	}

	/**
	 * Represents a SQL query with arguments.
	 */

	public static class DatabaseQuery {

		/**
		 * The query.
		 */

		private final String query;

		/**
		 * The arguments.
		 */

		private final Object[] arguments;

		/**
		 * Creates a new SQL query instance.
		 *
		 * @param query The query.
		 * @param arguments The arguments.
		 */

		public DatabaseQuery(final String query, final Object... arguments) {
			this.query = query;
			this.arguments = arguments;
		}

		/**
		 * Returns the query.
		 *
		 * @return The query.
		 */

		public final String getQuery() {
			return query;
		}

		/**
		 * Returns the arguments.
		 *
		 * @return The arguments.
		 */

		public final Object[] getArguments() {
			return arguments;
		}

	}

}

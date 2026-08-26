package me.elian.ezauctions.command;

import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Subcommand;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuctionCommandMetadataTest {
	@Test
	void defaultAuctionCommandRemainsAvailable() throws Exception {
		Method method = AuctionCommand.class.getMethod("open", Player.class);
		assertNotNull(method.getAnnotation(Default.class));
	}

	@Test
	void leaveCommandUsesDedicatedPlayerPermission() throws Exception {
		Method method = AuctionCommand.class.getMethod("leave", Player.class);
		assertEquals("leave", method.getAnnotation(Subcommand.class).value());
		assertEquals("rookieauctions.session.leave",
				method.getAnnotation(CommandPermission.class).value());
	}

	@Test
	void venueCommandsUseDedicatedAdminPermission() throws Exception {
		assertVenueCommand("setVenuePoint", new Class<?>[]{Player.class, String.class},
				"admin venue set");
		assertVenueCommand("previewVenue", new Class<?>[]{Player.class},
				"admin venue preview");
		assertVenueCommand("validateVenue", new Class<?>[]{Player.class},
				"admin venue validate");
		assertVenueCommand("enableVenue", new Class<?>[]{Player.class},
				"admin venue enable");
		assertVenueCommand("disableVenue", new Class<?>[]{Player.class},
				"admin venue disable");
	}

	@Test
	void sessionStatusUsesDedicatedAdminPermission() throws Exception {
		Method method = AuctionCommand.class.getMethod("sessionStatus", Player.class);
		assertEquals("admin session status", method.getAnnotation(Subcommand.class).value());
		assertEquals("rookieauctions.admin.session",
				method.getAnnotation(CommandPermission.class).value());
	}

	private void assertVenueCommand(String methodName, Class<?>[] parameterTypes,
	                                String expectedSubcommand) throws Exception {
		Method method = AuctionCommand.class.getMethod(methodName, parameterTypes);
		assertEquals(expectedSubcommand, method.getAnnotation(Subcommand.class).value());
		assertEquals("rookieauctions.admin.venue",
				method.getAnnotation(CommandPermission.class).value());
	}
}

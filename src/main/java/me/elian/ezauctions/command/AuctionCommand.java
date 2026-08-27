package me.elian.ezauctions.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.elian.ezauctions.gui.AuctionGuiController;
import me.elian.ezauctions.controller.ConfigController;
import me.elian.ezauctions.controller.MessageController;
import me.elian.ezauctions.controller.session.AuctionSessionController;
import me.elian.ezauctions.immersive.AttendanceResult;
import me.elian.ezauctions.immersive.AttendanceService;
import me.elian.ezauctions.immersive.VenueConfig;
import me.elian.ezauctions.immersive.VenueDisplayController;
import me.elian.ezauctions.immersive.VenueLocationType;
import me.elian.ezauctions.immersive.VenueValidation;
import me.elian.ezauctions.scheduler.TaskScheduler;
import me.elian.ezauctions.session.AuctionSessionView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;

@Singleton
@CommandAlias("auction")
@Description("Open the auction GUI")
public final class AuctionCommand extends BaseCommand {
	private final AuctionGuiController gui;
	private final VenueConfig venueConfig;
	private final VenueDisplayController venueDisplay;
	private final AttendanceService attendance;
	private final TaskScheduler scheduler;
	private final AuctionSessionController sessions;
	private final ConfigController config;
	private final MessageController messages;

	@Inject
	public AuctionCommand(AuctionGuiController gui, VenueConfig venueConfig,
	                      VenueDisplayController venueDisplay, AttendanceService attendance,
	                      TaskScheduler scheduler, AuctionSessionController sessions,
	                      ConfigController config, MessageController messages) {
		this.gui = gui;
		this.venueConfig = venueConfig;
		this.venueDisplay = venueDisplay;
		this.attendance = attendance;
		this.scheduler = scheduler;
		this.sessions = sessions;
		this.config = config;
		this.messages = messages;
	}

	@Default
	public void open(Player player) {
		gui.open(player);
	}

	@Subcommand("reload")
	@CommandPermission("rookieauctions.auction.reload")
	@Description("Reload configuration, messages and auction session scheduling")
	public void reload(CommandSender sender) {
		try {
			config.reloadConfiguration();
			messages.reloadMessages();
			sessions.reloadSchedule();
			venueDisplay.refresh();
			sessions.requestImmediateMaintenance();
			sendCommandMessage(sender, Component.text("RookieAuctions configuration reloaded.", NamedTextColor.GREEN));
		} catch (IOException | RuntimeException error) {
			sendCommandMessage(sender, Component.text("Could not reload RookieAuctions: "
				+ String.valueOf(error.getMessage()), NamedTextColor.RED));
		}
	}

	@Subcommand("leave")
	@CommandPermission("rookieauctions.session.leave")
	@Description("Leave immersive auction mode and return to your previous location")
	public void leave(Player player) {
		attendance.leave(player).whenComplete((result, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (!player.isOnline()) {
						return;
					}
					if (error != null || result == null) {
						player.sendMessage(Component.text(
								"退出拍卖模式失败，请联系管理员。", NamedTextColor.RED));
						return;
					}
					player.sendMessage(formatLeaveResult(result));
				}, player));
	}


	@Subcommand("admin session force-start")
	@CommandPermission("rookieauctions.admin.session.force-start")
	@Description("Force-start the earliest eligible auction session")
	public void forceStartSession(CommandSender sender) {
		sessions.forceStartNextSession().whenComplete((result, error) -> {
			if (error != null) {
				sendCommandMessage(sender, Component.text("Could not force-start a session: "
					+ String.valueOf(error.getMessage()), NamedTextColor.RED));
				return;
			}
			NamedTextColor color = result.started() ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
			sendCommandMessage(sender, Component.text(result.message(), color));
		});
	}
	@Subcommand("admin venue set")
	@CommandPermission("rookieauctions.admin.venue")
	@CommandCompletion("buyer-spawn|item-display|info-display|corner1|corner2")
	@Syntax("<buyer-spawn|item-display|info-display|corner1|corner2>")
	@Description("Set a venue point to your current position and view direction")
	public void setVenuePoint(Player player, String pointName) {
		VenueLocationType type = VenueLocationType.fromCommandArgument(pointName).orElse(null);
		if (type == null) {
			player.sendMessage(Component.text(
					"未知场地点。可用值：buyer-spawn、item-display、info-display、corner1、corner2",
					NamedTextColor.RED));
			return;
		}

		try {
			venueConfig.setPoint(type, player.getLocation());
			venueDisplay.clear();
			player.sendMessage(Component.text("已设置场地点 " + type.configKey() + "。",
					NamedTextColor.GREEN));
			VenueValidation validation = venueConfig.validate();
			if (!validation.valid()) {
				player.sendMessage(Component.text("场地尚未完整：" + validation.summary(),
						NamedTextColor.YELLOW));
			}
		} catch (IOException error) {
			player.sendMessage(Component.text("无法保存场地配置，请检查服务器日志。",
					NamedTextColor.RED));
		}
	}

	@Subcommand("admin venue preview")
	@CommandPermission("rookieauctions.admin.venue")
	@Description("Preview the configured displays for ten seconds")
	public void previewVenue(Player player) {
		VenueValidation validation = venueConfig.validate();
		if (!validation.valid()) {
			sendVenueValidation(player, validation);
			return;
		}
		venueDisplay.preview();
		player.sendMessage(Component.text(
				"场地预览已生成，将持续 10 秒；第 5 秒触发成交动画和音效。",
				NamedTextColor.GREEN));
	}

	@Subcommand("admin venue validate")
	@CommandPermission("rookieauctions.admin.venue")
	@Description("Validate all immersive venue locations")
	public void validateVenue(Player player) {
		VenueValidation validation = venueConfig.validate();
		sendVenueValidation(player, validation);
		if (validation.valid()) {
			sessions.retryBlockedNow();
		}
	}

	@Subcommand("admin venue enable")
	@CommandPermission("rookieauctions.admin.venue")
	@Description("Enable immersive sessions after venue validation")
	public void enableVenue(Player player) {
		try {
			venueConfig.setEnabled(true);
			venueDisplay.preview();
			sessions.retryBlockedNow();
			player.sendMessage(Component.text(
					"沉浸式拍卖场地已启用；预览将持续 10 秒。",
					NamedTextColor.GREEN));
		} catch (IllegalStateException error) {
			player.sendMessage(Component.text("无法启用：" + error.getMessage(), NamedTextColor.RED));
		} catch (IOException error) {
			player.sendMessage(Component.text("无法保存启用状态，请检查服务器日志。",
					NamedTextColor.RED));
		}
	}

	@Subcommand("admin session status")
	@CommandPermission("rookieauctions.admin.session")
	@Description("Show the active session and the next submission windows")
	public void sessionStatus(Player player) {
		AuctionSessionView active = sessions.activeSession().orElse(null);
		if (active == null) {
			player.sendMessage(Component.text("当前没有正在运行的拍卖场次。", NamedTextColor.YELLOW));
		} else {
			player.sendMessage(Component.text("当前场次：" + active.sessionKey()
					+ "，状态 " + active.state() + "，拍品 " + active.lotCount()
					+ "/" + active.capacity(), NamedTextColor.GREEN));
		}
		sessions.futureSessionViews().whenComplete((views, error) ->
				scheduler.runPlayerRegionTask(() -> {
					if (!player.isOnline()) {
						return;
					}
					if (error != null || views == null) {
						player.sendMessage(Component.text("无法读取未来场次。", NamedTextColor.RED));
						return;
					}
					for (AuctionSessionView view : views) {
						player.sendMessage(Component.text("- " + view.sessionKey() + "  " + view.state()
								+ "  " + view.lotCount() + "/" + view.capacity()
								+ "  开始 " + view.scheduledStart(), NamedTextColor.GRAY));
					}
				}, player));
	}

	@Subcommand("admin venue disable")
	@CommandPermission("rookieauctions.admin.venue")
	@Description("Disable immersive sessions and remove venue displays")
	public void disableVenue(Player player) {
		try {
			venueConfig.setEnabled(false);
			venueDisplay.clear();
			player.sendMessage(Component.text("沉浸式拍卖场地已停用。", NamedTextColor.YELLOW));
		} catch (IOException error) {
			player.sendMessage(Component.text("无法保存停用状态，请检查服务器日志。",
					NamedTextColor.RED));
		}
	}

	private void sendCommandMessage(CommandSender sender, Component message) {
		if (sender instanceof Player player) {
			scheduler.runPlayerRegionTask(() -> {
				if (player.isOnline()) {
					player.sendMessage(message);
				}
				}, player);
		} else {
			scheduler.runSyncTask(() -> sender.sendMessage(message));
		}
	}

	private void sendVenueValidation(Player player, VenueValidation validation) {
		if (validation.valid()) {
			player.sendMessage(Component.text("场地配置有效，可以启用沉浸式拍卖。",
					NamedTextColor.GREEN));
			return;
		}
		player.sendMessage(Component.text("场地配置无效：", NamedTextColor.RED));
		for (String error : validation.errors()) {
			player.sendMessage(Component.text("- " + error, NamedTextColor.YELLOW));
		}
	}

	private Component formatLeaveResult(AttendanceResult result) {
		return switch (result.status()) {
			case LEFT -> Component.text("已退出拍卖模式并返回原位置。", NamedTextColor.GREEN);
			case RETURN_DEFERRED -> Component.text(
					"已退出拍卖模式；返程点暂不可用，系统会在你下次上线时重试。",
					NamedTextColor.YELLOW);
			case NOT_ACTIVE, NOT_REGISTERED, ALREADY_LEFT -> Component.text(
					"你当前不在拍卖模式中。", NamedTextColor.YELLOW);
			case PLAYER_BUSY -> Component.text("出席状态正在处理中，请稍后重试。",
					NamedTextColor.YELLOW);
			case TELEPORT_FAILED -> Component.text(
					"已退出竞价状态，但返回原位置失败；系统会保留返程记录。",
					NamedTextColor.RED);
			case RETURN_LOCATION_MISSING -> Component.text(
					"已退出竞价状态，但返程点缺失，请联系管理员。", NamedTextColor.RED);
			case PERSISTENCE_FAILED -> Component.text(
					"退出拍卖模式时无法保存状态，请联系管理员。", NamedTextColor.RED);
			default -> Component.text("当前无法退出拍卖模式（" + result.status().name() + "）。",
					NamedTextColor.RED);
		};
	}
}
